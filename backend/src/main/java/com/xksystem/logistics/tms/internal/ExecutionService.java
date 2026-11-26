package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.fms.VehicleQueries;
import com.xksystem.logistics.tms.DispatchEligibilityPolicy;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.ResourceKind;
import com.xksystem.logistics.tms.ResourcePosition;
import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.TmsFailure;
import com.xksystem.logistics.tms.execution.*;
import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.upms.UpmsAuthorization;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import com.xksystem.logistics.upms.security.UpmsSessions;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionService {
    private final TmsStore dispatch;
    private final ExecutionStore execution;
    private final UpmsAuthorization authorization;
    private final DriverQueries drivers;
    private final VehicleQueries vehicles;
    private final TaskQueries tasks;
    private final DispatchEligibilityPolicy policy;
    private final Clock clock;
    public ExecutionService(TmsStore dispatch,ExecutionStore execution,UpmsAuthorization authorization,DriverQueries drivers,
            VehicleQueries vehicles,TaskQueries tasks,DispatchEligibilityPolicy policy,Clock clock) {
        this.dispatch=dispatch;this.execution=execution;this.authorization=authorization;this.drivers=drivers;
        this.vehicles=vehicles;this.tasks=tasks;this.policy=policy;this.clock=clock;
    }
    public record Written(ExecutionDtos.DepartureResult data,boolean replayed) {}
    private record Owned(UUID actorId,UUID driverId,TmsDtos.Assignment assignment) {}

    /** Lock-capable read; WAITING has no database/event/receipt side effect. */
    @Transactional
    public ExecutionDtos.Detail detail(SessionPrincipal principal,UUID assignmentId,HttpSession originalSession) {
        var assignment=current(principal,assignmentId,originalSession).assignment();var source=assignment.source();
        return new ExecutionDtos.Detail(assignment.id(),source.taskId(),source.taskNo(),source.item(),source.taskKind(),
                source.pickupName(),source.pickupAddress(),source.deliveryName(),source.deliveryAddress(),source.siteContact(),
                source.plannedStartAt(),source.plannedEndAt(),assignment.assignedAt(),assignment.policyMode(),execution.view(assignmentId));
    }
    @Transactional
    public Written depart(SessionPrincipal principal,UUID assignmentId,byte[] raw,String submittedKey,HttpSession originalSession) {
        var preliminary=authorization.requireDriverSelf(principal);
        var input=ExecutionValidation.command(raw);String key=ExecutionValidation.key(submittedKey);
        String path="/api/v1/tms/my-tasks/"+assignmentId+"/depart";
        if(!dispatch.tryKey(preliminary.id(),path,key))
            throw ExecutionFailure.conflict("IDEMPOTENCY_IN_PROGRESS","相同重送識別仍在處理");
        var owned=current(principal,assignmentId,originalSession);var assignment=owned.assignment();
        var prior=execution.receipt(owned.actorId(),path,key);
        if(prior.isPresent()) {
            if(!prior.get().driverId().equals(owned.driverId())
                    ||!prior.get().result().assignmentId().equals(assignmentId)
                    ||!prior.get().result().taskId().equals(assignment.taskId()))throw ExecutionFailure.missing();
            if(!prior.get().hash().equals(input.hash()))
                throw ExecutionFailure.conflict("IDEMPOTENCY_KEY_REUSED","此重送識別已用於不同內容");
            return new Written(prior.get().result(),true);
        }
        var view=execution.view(assignmentId);
        if(view.version()!=input.expectedVersion())
            throw ExecutionFailure.conflict("VERSION_CONFLICT","執行版本已更新，請重新讀取",view.version());
        if(view.phase()!=ExecutionPhase.WAITING||view.version()>=com.xksystem.logistics.tms.TmsValidation.MAX)
            throw ExecutionFailure.conflict("INVALID_STATE","目前階段不能出發");
        if(policy.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY||assignment.policyMode()!=PolicyMode.SYNTHETIC_TEST_ONLY)
            throw ExecutionFailure.conflict("POLICY_NOT_CONFIRMED","尚無已核准的正式出發適用政策");
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var result=new ExecutionDtos.DepartureResult(assignment.id(),assignment.taskId(),
                new ExecutionDtos.View(ExecutionPhase.DEPARTED,1,now));
        execution.depart(owned.actorId(),owned.driverId(),path,key,input.hash(),result,now);
        return new Written(result,false);
    }

    private Owned current(SessionPrincipal principal,UUID assignmentId,HttpSession originalSession) {
        var actor=authorization.requireDriverSelfWithLocks(principal);
        UpmsSessions.requireCurrent(originalSession,principal,clock);
        var driver=drivers.currentBoundDriverWithLock(actor.id()).orElseThrow(ExecutionFailure::missing);
        try {
            var hint=dispatch.assignment(assignmentId,false);
            if(!hint.status().equals("ASSIGNED")||!hint.driver().id().equals(driver.id()))throw ExecutionFailure.missing();
            var vehicleIds=new TreeSet<UUID>(Comparator.comparing(UUID::toString));
            vehicleIds.add(hint.mainVehicle().id());if(hint.trailer()!=null)vehicleIds.add(hint.trailer().id());
            for(UUID vehicleId:vehicleIds)vehicles.lockCurrentForDispatch(vehicleId);
            var source=tasks.lockCurrentForDispatch(hint.taskId());var anchor=dispatch.anchor(hint.taskId(),true);
            var assignment=dispatch.assignment(assignmentId,true);
            if(!sameIdentity(hint,assignment)||!assignment.status().equals("ASSIGNED")
                    ||dispatch.current(assignment.taskId()).filter(value->value.id().equals(assignmentId)).isEmpty()
                    ||!source.accepted()||source.source().orderVersion()!=anchor.sourceOrderVersion()
                    ||!source.source().equals(assignment.source())||!source.source().orderId().equals(anchor.orderId())
                    ||!source.source().taskId().equals(anchor.taskId()))throw ExecutionFailure.missing();
            completeOccupation(assignment);
            return new Owned(actor.id(),driver.id(),assignment);
        } catch(TmsFailure failure) {
            if(failure.status()==404)throw ExecutionFailure.missing();throw failure;
        } catch(UpmsException failure) {
            if(failure.status()==404)throw ExecutionFailure.missing();throw failure;
        }
    }
    private void completeOccupation(TmsDtos.Assignment assignment) {
        var expected=new EnumMap<ResourcePosition,UUID>(ResourcePosition.class);
        expected.put(ResourcePosition.DRIVER,assignment.driver().id());expected.put(ResourcePosition.MAIN,assignment.mainVehicle().id());
        if(assignment.trailer()!=null)expected.put(ResourcePosition.TRAILER,assignment.trailer().id());
        if(assignment.reservations().size()!=expected.size())throw ExecutionFailure.missing();
        for(var reservation:assignment.reservations()) {
            UUID id=expected.remove(reservation.position());
            ResourceKind kind=reservation.position()==ResourcePosition.DRIVER?ResourceKind.DRIVER:ResourceKind.VEHICLE;
            if(id==null||!id.equals(reservation.resourceId())||reservation.kind()!=kind
                    ||reservation.releasedAt()!=null||reservation.releaseReason()!=null)throw ExecutionFailure.missing();
            var occupied=dispatch.occupation(kind,id,true).orElseThrow(ExecutionFailure::missing);
            if(!occupied.assignmentId().equals(assignment.id())||!occupied.taskId().equals(assignment.taskId()))throw ExecutionFailure.missing();
        }
        if(!expected.isEmpty())throw ExecutionFailure.missing();
    }
    private static boolean sameIdentity(TmsDtos.Assignment first,TmsDtos.Assignment second) {
        return first.taskId().equals(second.taskId())&&first.driver().id().equals(second.driver().id())
                &&first.mainVehicle().id().equals(second.mainVehicle().id())
                &&Objects.equals(first.trailer()==null?null:first.trailer().id(),second.trailer()==null?null:second.trailer().id());
    }
}
