package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.fms.*;
import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.upms.UpmsAuthorization;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TmsService {
    private final TmsStore store;private final ExecutionStore execution;private final TaskQueries tasks;private final DriverQueries drivers;private final VehicleQueries vehicles;
    private final UpmsAuthorization authorization;private final DispatchEligibilityPolicy policy;private final Clock clock;
    public TmsService(TmsStore store,ExecutionStore execution,TaskQueries tasks,DriverQueries drivers,VehicleQueries vehicles,UpmsAuthorization authorization,DispatchEligibilityPolicy policy,Clock clock){this.store=store;this.execution=execution;this.tasks=tasks;this.drivers=drivers;this.vehicles=vehicles;this.authorization=authorization;this.policy=policy;this.clock=clock;}
    public record Written(TmsDtos.CommandResult data,boolean replayed) {}
    @Transactional
    public Written write(SessionPrincipal principal,UUID target,byte[] raw,String submittedKey,TmsValidation.Action action){
        var actor=authorization.requireDispatchManager(principal);var original=TmsValidation.parse(raw);var input=TmsValidation.command(original,action);
        String key=TmsValidation.key(submittedKey),hash=TmsValidation.hash(original);
        String path=action==TmsValidation.Action.ASSIGN?"/api/v1/tms/tasks/"+target+"/dispatch":"/api/v1/tms/dispatches/"+target+(action==TmsValidation.Action.REVOKE?"/revoke":"/reassign");
        if(!store.tryKey(actor.id(),path,key))throw TmsFailure.conflict("IDEMPOTENCY_IN_PROGRESS","相同重送識別仍在處理");
        var prior=store.replay(actor.id(),path,key);
        if(prior.isPresent()){
            authorization.requireDispatchManagerForWrite(principal);
            if(!prior.get().hash().equals(hash))throw TmsFailure.conflict("IDEMPOTENCY_KEY_REUSED","此重送識別已用於不同內容");
            store.anchor(prior.get().taskId(),false);return new Written(prior.get().result(),true);
        }
        actor=authorization.requireDispatchManagerForWrite(principal);
        TmsDtos.Assignment hint=action==TmsValidation.Action.ASSIGN?null:store.assignment(target,false);
        UUID taskId=hint==null?target:hint.taskId();
        try{store.anchor(taskId,false);}catch(TmsFailure missing){
            if(missing.status()==404&&tasks.find(taskId).isPresent())throw TmsFailure.conflict("INVALID_STATE","任務尚未形成已受理的派工來源");throw missing;
        }
        var driverIds=new TreeSet<UUID>(Comparator.comparing(UUID::toString));var vehicleIds=new TreeSet<UUID>(Comparator.comparing(UUID::toString));
        if(hint!=null){driverIds.add(hint.driver().id());vehicleIds.add(hint.mainVehicle().id());if(hint.trailer()!=null)vehicleIds.add(hint.trailer().id());}
        if(action!=TmsValidation.Action.REVOKE){driverIds.add(input.driverId());vehicleIds.add(input.mainVehicleId());if(input.trailerId()!=null)vehicleIds.add(input.trailerId());}
        var lockedDrivers=new HashMap<UUID,DriverQueries.Snapshot>();var lockedVehicles=new HashMap<UUID,VehicleQueries.Snapshot>();
        for(UUID id:driverIds)lockedDrivers.put(id,drivers.lockCurrentForDispatch(id));
        for(UUID id:vehicleIds)lockedVehicles.put(id,vehicles.lockCurrentForDispatch(id));
        var source=tasks.lockCurrentForDispatch(taskId);var anchor=store.anchor(taskId,true);
        if(anchor.generation()!=input.expectedGeneration())throw TmsFailure.conflict("VERSION_CONFLICT","任務派工版本已更新，請重新讀取",anchor.generation());
        if(anchor.generation()>=TmsValidation.MAX)throw TmsFailure.conflict("INVALID_STATE","任務派工版本已達上限");
        TmsDtos.Assignment old=null;
        if(hint!=null){old=store.assignment(target,true);if(!sameIdentity(hint,old)||!old.status().equals("ASSIGNED")||store.current(taskId).filter(a->a.id().equals(target)).isEmpty())throw TmsFailure.conflict("INVALID_STATE","目前派工不能撤派或改派");}
        else if(store.current(taskId).isPresent())throw TmsFailure.conflict("INVALID_STATE","此任務已有有效派工");
        // Historical successful manager receipts are handled above. Only a new terminal mutation
        // must reject an execution that has departed; never release or exchange its resources.
        if(old!=null&&execution.started(old.id()))throw TmsFailure.conflict("INVALID_STATE","任務已出發，尚無核准的撤改處理流程");
        if(action==TmsValidation.Action.REASSIGN&&sameResources(old,input))throw TmsFailure.invalid("resources","改派必須至少變更一項資源");
        if(action!=TmsValidation.Action.REVOKE){
            requireSource(anchor,source);
            var driver=lockedDrivers.get(input.driverId());var main=lockedVehicles.get(input.mainVehicleId());var trailer=input.trailerId()==null?null:lockedVehicles.get(input.trailerId());
            if(!available(driver)||!available(main)||trailer!=null&&!available(trailer))throw TmsFailure.conflict("MASTER_UNAVAILABLE","資源行政來源目前不可用");
            if(policy.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY||!policy.driverApproved(driver)||!policy.vehicleApproved(main,ResourcePosition.MAIN)
                    ||trailer!=null&&!policy.vehicleApproved(trailer,ResourcePosition.TRAILER)||!policy.combinationApproved(source.source(),driver,main,trailer))
                throw TmsFailure.conflict("POLICY_NOT_CONFIRMED","尚無已核准的正式組合與適格政策");
            requireFree(ResourceKind.DRIVER,input.driverId(),old);requireFree(ResourceKind.VEHICLE,input.mainVehicleId(),old);if(input.trailerId()!=null)requireFree(ResourceKind.VEHICLE,input.trailerId(),old);
        }
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);long generation=anchor.generation()+1;UUID assignmentId;
        if(old!=null)store.end(old,action==TmsValidation.Action.REVOKE?"REVOKED":"SUPERSEDED",generation,actor.id(),now,input.reason());
        if(action==TmsValidation.Action.REVOKE)assignmentId=old.id();
        else{
            assignmentId=UUID.randomUUID();var driver=lockedDrivers.get(input.driverId());var main=lockedVehicles.get(input.mainVehicleId());var trailer=input.trailerId()==null?null:lockedVehicles.get(input.trailerId());
            store.createAssignment(assignmentId,anchor,generation,new TmsDtos.DriverSnapshot(driver.id(),driver.driverCode(),driver.name(),driver.version()),vehicle(main),trailer==null?null:vehicle(trailer),source.source(),policy.mode(),actor.id(),now,input.reason(),old==null?null:old.id());
        }
        store.advance(anchor);
        String eventAction=switch(action){case ASSIGN->"ASSIGNED";case REVOKE->"REVOKED";case REASSIGN->"REASSIGNED";};
        var event=new TmsDtos.Event(UUID.randomUUID(),eventAction,assignmentId,action==TmsValidation.Action.REASSIGN?old.id():null,anchor.generation(),generation,actor.id(),now,input.reason());
        var result=new TmsDtos.CommandResult(taskId,generation,store.assignment(assignmentId,event));
        store.event(taskId,event,result);store.remember(actor.id(),path,key,hash,result,now);return new Written(result,false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TmsDtos.Page<TmsDtos.TaskSummary> tasks(SessionPrincipal principal,TmsQuery query){
        authorization.requireDispatchManager(principal);var page=store.anchors(query);var sources=tasks.bulk(page.content().stream().map(TmsStore.Anchor::taskId).toList());
        return new TmsDtos.Page<>(page.content().stream().map(anchor->summary(anchor,Optional.ofNullable(sources.get(anchor.taskId())).orElseThrow(()->TmsFailure.conflict("INVALID_STATE","任務來源無法讀取")))).toList(),page.page(),page.size(),page.totalElements(),page.totalPages());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TmsDtos.DispatchDetail detail(SessionPrincipal principal,UUID taskId,TmsQuery query){
        authorization.requireDispatchManager(principal);var anchor=store.anchor(taskId,false);var source=tasks.find(taskId).orElseThrow(TmsFailure::missing);
        return new TmsDtos.DispatchDetail(summary(anchor,source),store.current(taskId).orElse(null),store.history(taskId,query.page(),query.size()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TmsDtos.Page<TmsDtos.ResourceCandidate> resources(SessionPrincipal principal,TmsQuery query){
        authorization.requireDispatchManager(principal);var anchor=store.anchor(query.taskId(),false);var source=tasks.find(query.taskId()).orElseThrow(TmsFailure::missing);
        UUID reusable=null;if(query.assignmentId()!=null){
            TmsDtos.Assignment assignment;try{assignment=store.assignment(query.assignmentId(),false);}catch(TmsFailure failure){if(failure.status()==404)throw TmsFailure.invalid("assignmentId","請提供此任務目前有效派工");throw failure;}
            if(!assignment.taskId().equals(query.taskId())||!assignment.status().equals("ASSIGNED")||!store.effective(assignment))throw TmsFailure.invalid("assignmentId","改派資源候選需要此任務目前有效派工");reusable=assignment.id();}
        final UUID own=reusable;
        if(query.position()==ResourcePosition.DRIVER){var page=drivers.pageForDispatch(query.page(),query.size(),query.q());
            return new TmsDtos.Page<>(page.getContent().stream().map(driver->candidate(driver.id(),ResourceKind.DRIVER,query.position(),driver.driverCode(),driver.name(),driver.version(),available(driver),policy.driverApproved(driver),anchor,source,own)).toList(),page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages());}
        var page=vehicles.pageForDispatch(query.page(),query.size(),query.q());
        return new TmsDtos.Page<>(page.getContent().stream().map(value->candidate(value.id(),ResourceKind.VEHICLE,query.position(),value.plateNo(),value.vehicleTypeLabel(),value.version(),available(value),policy.vehicleApproved(value,query.position()),anchor,source,own)).toList(),page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages());
    }
    @Transactional
    public TmsDtos.Page<TmsDtos.MyTask> mine(SessionPrincipal principal,TmsQuery query){
        var actor=authorization.requireDriverSelfWithLocks(principal);var driver=drivers.currentBoundDriverWithLock(actor.id()).orElseThrow(TmsFailure::missing);
        var page=store.self(driver.id(),query.page(),query.size());
        return new TmsDtos.Page<>(page.content().stream().map(assignment->{var s=assignment.source();return new TmsDtos.MyTask(assignment.id(),s.taskId(),s.taskNo(),s.item(),s.taskKind(),s.pickupName(),s.pickupAddress(),s.deliveryName(),s.deliveryAddress(),s.siteContact(),s.plannedStartAt(),s.plannedEndAt(),assignment.assignedAt(),assignment.policyMode());}).toList(),page.page(),page.size(),page.totalElements(),page.totalPages());
    }
    private TmsDtos.TaskSummary summary(TmsStore.Anchor anchor,TaskQueries.Current current){
        var source=current.source();var assignment=store.current(anchor.taskId()).orElse(null);var blockers=sourceBlockers(anchor,current);
        if(policy.mode()==PolicyMode.UNCONFIRMED)blockers.add("POLICY_NOT_CONFIRMED");if(assignment!=null)blockers.add("ALREADY_ASSIGNED");
        return new TmsDtos.TaskSummary(anchor.taskId(),anchor.taskNo(),anchor.orderId(),anchor.orderNo(),source.orderType(),source.orderVersion(),anchor.generation(),source.taskKind(),source.pickupName(),source.deliveryName(),current.prerequisiteReady(),assignment==null?null:assignment.id(),policy.mode(),List.copyOf(blockers));
    }
    private TmsDtos.ResourceCandidate candidate(UUID id,ResourceKind kind,ResourcePosition position,String code,String label,long version,boolean admin,boolean approved,TmsStore.Anchor anchor,TaskQueries.Current source,UUID own){
        var occupied=store.occupation(kind,id,false).orElse(null);var blockers=sourceBlockers(anchor,source);if(!admin)blockers.add("ADMIN_UNAVAILABLE");
        if(policy.mode()==PolicyMode.UNCONFIRMED||!approved)blockers.add("POLICY_NOT_CONFIRMED");if(occupied!=null&&!occupied.assignmentId().equals(own))blockers.add("RESOURCE_OCCUPIED");
        return new TmsDtos.ResourceCandidate(id,kind,position,code,label,version,admin,occupied!=null,occupied,policy.mode(),approved&&policy.mode()==PolicyMode.SYNTHETIC_TEST_ONLY,List.copyOf(blockers));
    }
    private static ArrayList<String> sourceBlockers(TmsStore.Anchor anchor,TaskQueries.Current source){var blockers=new ArrayList<String>();if(!matches(anchor,source))blockers.add("SOURCE_NOT_ACCEPTED");if(!source.prerequisiteReady())blockers.add("PREREQUISITE_NOT_READY");return blockers;}
    private static boolean matches(TmsStore.Anchor anchor,TaskQueries.Current source){return source.accepted()&&source.source().taskId().equals(anchor.taskId())&&source.source().orderId().equals(anchor.orderId())&&source.source().orderVersion()==anchor.sourceOrderVersion();}
    private static void requireSource(TmsStore.Anchor anchor,TaskQueries.Current source){if(!matches(anchor,source)||source.source().item()==null||!("CONTAINER".equals(source.source().item().itemKind())||"CARGO".equals(source.source().item().itemKind())))throw TmsFailure.conflict("INVALID_STATE","任務來源已變更或不是 OPEN+ACCEPTED");if(!source.prerequisiteReady())throw TmsFailure.conflict("PREREQUISITE_NOT_READY","前置任務尚無已核准的完成或交接證據");}
    private void requireFree(ResourceKind kind,UUID id,TmsDtos.Assignment own){var occupation=store.occupation(kind,id,true);if(occupation.isPresent()&&(own==null||!occupation.get().assignmentId().equals(own.id())))throw TmsFailure.conflict("RESOURCE_OCCUPIED","資源已有其他有效占用");}
    private static boolean available(DriverQueries.Snapshot d){return d.employmentStatus()==EmploymentStatus.ACTIVE&&d.dutyStatus()==DutyStatus.ON_DUTY;}
    private static boolean available(VehicleQueries.Snapshot v){return v.administrativeStatus()==AdministrativeStatus.AVAILABLE;}
    private static TmsDtos.VehicleSnapshot vehicle(VehicleQueries.Snapshot v){return new TmsDtos.VehicleSnapshot(v.id(),v.plateNo(),v.vehicleType(),v.vehicleTypeCode(),v.vehicleTypeLabel(),v.version());}
    private static boolean sameIdentity(TmsDtos.Assignment a,TmsDtos.Assignment b){return a.taskId().equals(b.taskId())&&a.driver().id().equals(b.driver().id())&&a.mainVehicle().id().equals(b.mainVehicle().id())&&Objects.equals(a.trailer()==null?null:a.trailer().id(),b.trailer()==null?null:b.trailer().id());}
    private static boolean sameResources(TmsDtos.Assignment old,TmsValidation.Command c){return old.driver().id().equals(c.driverId())&&old.mainVehicle().id().equals(c.mainVehicleId())&&Objects.equals(old.trailer()==null?null:old.trailer().id(),c.trailerId());}
}
