package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.fms.VehicleQueries;
import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.tms.evidence.*;
import com.xksystem.logistics.tms.exceptions.*;
import com.xksystem.logistics.tms.execution.*;
import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.upms.*;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import com.xksystem.logistics.upms.security.UpmsSessions;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Service @Transactional
public class ExceptionCaseService implements ExceptionQueries {
    private final ExceptionStore cases;private final ExecutionService access;private final ExecutionStore execution;private final TmsStore dispatch;
    private final EvidenceStore evidence;private final EvidenceStorage storage;private final ExceptionPolicy policy;private final DispatchEligibilityPolicy dispatchPolicy;
    private final UpmsAuthorization authorization;private final DriverQueries drivers;private final VehicleQueries vehicles;private final TaskQueries tasks;private final Clock clock;
    public ExceptionCaseService(ExceptionStore cases,ExecutionService access,ExecutionStore execution,TmsStore dispatch,EvidenceStore evidence,EvidenceStorage storage,
            ExceptionPolicy policy,DispatchEligibilityPolicy dispatchPolicy,UpmsAuthorization authorization,DriverQueries drivers,VehicleQueries vehicles,TaskQueries tasks,Clock clock) {
        this.cases=cases;this.access=access;this.execution=execution;this.dispatch=dispatch;this.evidence=evidence;this.storage=storage;this.policy=policy;
        this.dispatchPolicy=dispatchPolicy;this.authorization=authorization;this.drivers=drivers;this.vehicles=vehicles;this.tasks=tasks;this.clock=clock;
    }
    public record Written<T>(T data,boolean replayed) {}
    public TmsDtos.Page<ExceptionDtos.Summary> driverList(SessionPrincipal p,UUID assignment,ExceptionValidation.Query query,HttpSession session){own(p,assignment,session);return cases.page(query,assignment);}
    public ExceptionDtos.Detail driverDetail(SessionPrincipal p,UUID assignment,UUID id,HttpSession session){var owned=own(p,assignment,session);var state=ownedState(owned,id,false);return detail(state,false,true);}
    public TmsDtos.Page<ExceptionDtos.DriverEvent> driverHistory(SessionPrincipal p,UUID assignment,UUID id,ExceptionValidation.Query query,HttpSession session) {
        ownedState(own(p,assignment,session),id,false);return TmsDtos.Page.of(cases.events(id,query).stream().map(cases::driverEvent).toList(),query.page(),query.size(),cases.eventCount(id));
    }
    public TmsDtos.Page<ExceptionDtos.Summary> managerList(SessionPrincipal p,ExceptionValidation.Query query,HttpSession session){manager(p,session);return cases.page(query,null);}
    public ExceptionDtos.Detail managerDetail(SessionPrincipal p,UUID id,HttpSession session){manager(p,session);var state=cases.state(id,false);return detail(state,true,isCurrent(state));}
    public TmsDtos.Page<ExceptionDtos.ManagerEvent> managerHistory(SessionPrincipal p,UUID id,ExceptionValidation.Query query,HttpSession session){manager(p,session);cases.state(id,false);return TmsDtos.Page.of(cases.events(id,query).stream().map(cases::managerEvent).toList(),query.page(),query.size(),cases.eventCount(id));}
    public TmsDtos.Page<ExceptionDtos.SupplementEvidence> managerEvidence(SessionPrincipal p,UUID id,ExceptionValidation.Query query,HttpSession session){manager(p,session);cases.state(id,false);return cases.evidencePage(id,query,evidence);}
    public byte[] managerContent(SessionPrincipal p,UUID id,UUID evidenceId,HttpSession session) {
        manager(p,session);var state=cases.state(id,false);
        if(!cases.belongs(id,evidenceId))throw ExceptionFailure.missing();
        if(policy.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY||storage.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY||!policy.managerCanRead(state.source(),state.driver()))throw policy();
        var stored=evidence.find(state.value().assignmentId(),evidenceId).orElseThrow(ExceptionFailure::missing);
        byte[] bytes=storage.load(stored.objectId());if(!integrity(stored.evidence(),bytes))throw new IllegalStateException("Private supplement integrity unavailable");return bytes;
    }
    public Written<ExceptionDtos.Record> report(SessionPrincipal p,UUID assignment,byte[] raw,String submittedKey,HttpSession session) {
        var actor=authorization.requireDriverSelf(p);var input=ExceptionValidation.report(raw);String path=self(assignment)+"/exceptions",key=ExceptionValidation.key(submittedKey);
        key(actor.id(),path,key);var owned=own(p,assignment,session);
        var prior=cases.receipt(owned.actorId(),path,key);
        if(prior.isPresent()){checkReceipt(prior.get(),null,assignment,owned.driverId(),input.hash());return new Written<>(cases.result(prior.get(),ExceptionDtos.Record.class),true);}
        var view=execution.view(assignment);executionVersion(view,input.version());if(!reported(view.phase()))throw state();
        requirePolicy(new ExceptionPolicy.Context(owned.assignment().source(),owned.assignment().driver(),view),owned.assignment().policyMode(),ExceptionPolicy.Action.REPORT,false);
        UUID id=UUID.randomUUID();Instant now=now();cases.report(id,owned,input.text(),now,policy.mode());var after=cases.state(id,false);
        cases.event(after,"REPORTED",null,input.text(),null,owned.actor(),now,after.value());cases.remember(after,owned.actorId(),null,path,key,input.hash(),after.value(),false,now);
        return new Written<>(after.value(),false);
    }
    public Written<ExceptionDtos.Record> requestSupplement(SessionPrincipal p,UUID id,byte[] raw,String submittedKey,HttpSession session){return manage(p,id,raw,submittedKey,session,false);}
    public Written<ExceptionDtos.Record> resolve(SessionPrincipal p,UUID id,byte[] raw,String submittedKey,HttpSession session){return manage(p,id,raw,submittedKey,session,true);}
    private Written<ExceptionDtos.Record> manage(SessionPrincipal p,UUID id,byte[] raw,String submittedKey,HttpSession session,boolean resolving) {
        var preliminary=authorization.requireDispatchManager(p);var input=ExceptionValidation.management(raw);
        String path="/api/v1/tms/exceptions/"+id+(resolving?"/resolve":"/supplement-requests"),key=ExceptionValidation.key(submittedKey);
        key(preliminary.id(),path,key);var actor=manager(p,session);var hint=cases.state(id,false);
        var prior=cases.receipt(actor.id(),path,key);
        if(prior.isPresent()){checkReceipt(prior.get(),id,hint.value().assignmentId(),hint.driverId(),input.hash());return new Written<>(cases.result(prior.get(),ExceptionDtos.Record.class),true);}
        lockCurrent(hint);var before=cases.state(id,true);exceptionVersion(before,input.version());
        if(before.value().status()!=ExceptionStatus.OPEN||before.value().activeRequest()!=null)throw state();
        var context=context(before);requirePolicy(context,before.policyMode(),resolving?ExceptionPolicy.Action.RESOLVE:ExceptionPolicy.Action.REQUEST_SUPPLEMENT,true);
        Instant now=now();UUID request=null;
        if(resolving) {
            var ids=cases.submittedEvidence(id);if(ids.isEmpty())throw supplement();
            var rule=rule(context);verify(before,null,ids,rule);
            cases.update(before.value(),ExceptionStatus.RESOLVED,null,input.text(),now);
        } else {
            request=UUID.randomUUID();cases.request(before,request,input.text(),actor,now,rule(context));
        }
        var after=cases.state(id,false);cases.event(after,resolving?"RESOLVED":"SUPPLEMENT_REQUESTED",before.value(),input.text(),request,actor,now,after.value());
        cases.remember(after,actor.id(),request,path,key,input.hash(),after.value(),false,now);return new Written<>(after.value(),false);
    }
    public Written<ExceptionDtos.UploadResult> upload(SessionPrincipal p,UUID assignment,UUID id,UUID request,long exceptionVersion,long expectedExecutionVersion,
            String media,byte[] bytes,String submittedKey,HttpSession session) {
        var actor=authorization.requireDriverSelf(p);String path=self(assignment)+"/exceptions/"+id+"/supplement-requests/"+request+"/evidence",key=ExceptionValidation.key(submittedKey);
        String hash=uploadHash(path,exceptionVersion,expectedExecutionVersion,media,bytes);key(actor.id(),path,key);
        var owned=own(p,assignment,session);var hint=ownedState(owned,id,false);cases.request(id,assignment,request);
        var prior=cases.receipt(owned.actorId(),path,key);
        if(prior.isPresent()){checkReceipt(prior.get(),id,assignment,owned.driverId(),hash);return new Written<>(cases.result(prior.get(),ExceptionDtos.UploadResult.class),true);}
        var before=ownedState(owned,id,true);exceptionVersion(before,exceptionVersion);var view=execution.view(assignment);executionVersion(view,expectedExecutionVersion);
        active(before,request);if(!reported(view.phase())||cases.requestEvidence(id,request).size()>=32)throw state();
        var context=context(before);requirePolicy(context,before.policyMode(),ExceptionPolicy.Action.UPLOAD_SUPPLEMENT,true);var rule=rule(context);validatePng(bytes,rule);
        Instant now=now();UUID object=UUID.randomUUID();var photo=new EvidenceDtos.Evidence(UUID.randomUUID(),assignment,before.value().taskId(),view.phase(),view.version(),media,bytes.length,EvidenceValidation.digest(bytes),now,"VERIFIED");
        storage.store(object,bytes,photo.sha256());evidence.insertMetadata(owned,object,photo,rule);
        cases.update(before.value(),ExceptionStatus.NEEDS_SUPPLEMENT,request,null,null);var after=cases.state(id,false);
        var result=new ExceptionDtos.UploadResult(id,assignment,after.value().taskId(),after.value().version(),request,photo);
        UUID event=cases.event(after,"SUPPLEMENT_EVIDENCE_UPLOADED",before.value(),null,request,owned.actor(),now,result);
        cases.attach(after,request,photo.id(),event);cases.remember(after,owned.actorId(),request,path,key,hash,result,true,now);return new Written<>(result,false);
    }
    public Written<ExceptionDtos.Record> submit(SessionPrincipal p,UUID assignment,UUID id,UUID request,byte[] raw,String submittedKey,HttpSession session) {
        var actor=authorization.requireDriverSelf(p);var input=ExceptionValidation.submit(raw);
        String path=self(assignment)+"/exceptions/"+id+"/supplement-requests/"+request+"/submit",key=ExceptionValidation.key(submittedKey);
        key(actor.id(),path,key);var owned=own(p,assignment,session);ownedState(owned,id,false);cases.request(id,assignment,request);
        var prior=cases.receipt(owned.actorId(),path,key);
        if(prior.isPresent()){checkReceipt(prior.get(),id,assignment,owned.driverId(),input.hash());return new Written<>(cases.result(prior.get(),ExceptionDtos.Record.class),true);}
        var before=ownedState(owned,id,true);exceptionVersion(before,input.version());active(before,request);
        var context=context(before);requirePolicy(context,before.policyMode(),ExceptionPolicy.Action.SUBMIT_SUPPLEMENT,true);var rule=rule(context);
        verify(before,request,input.evidenceIds(),rule);if(input.evidenceIds().size()<rule.minimumCount())throw supplement();
        Instant now=now();cases.update(before.value(),ExceptionStatus.OPEN,null,null,null);var after=cases.state(id,false);
        UUID event=cases.event(after,"SUPPLEMENT_SUBMITTED",before.value(),input.text(),request,owned.actor(),now,after.value());cases.selected(after,request,event,input.evidenceIds());
        cases.remember(after,owned.actorId(),request,path,key,input.hash(),after.value(),false,now);return new Written<>(after.value(),false);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Blocking lockBlockingForAssignment(UUID assignment) {
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalTransactionStateException("Exception blockers require a writable caller transaction");
        cases.lockAssignment(assignment);return new Blocking(assignment,cases.unresolved(assignment));
    }
    private ExecutionService.Owned own(SessionPrincipal p,UUID assignment,HttpSession session){try{return access.current(p,assignment,session);}catch(ExecutionFailure failure){if(failure.status()==404)throw ExceptionFailure.missing();throw failure;}}
    private ExceptionStore.State ownedState(ExecutionService.Owned owned,UUID id,boolean lock){var state=cases.state(id,lock);if(!state.value().assignmentId().equals(owned.assignment().id())||!state.driverId().equals(owned.driverId()))throw ExceptionFailure.missing();return state;}
    private AccountDto manager(SessionPrincipal p,HttpSession session){var actor=authorization.requireDispatchManagerForWrite(p);UpmsSessions.requireCurrent(session,p,clock);return actor;}
    private void lockCurrent(ExceptionStore.State state) {
        try {
            var hint=dispatch.assignment(state.value().assignmentId(),false);drivers.lockCurrentForDispatch(hint.driver().id());
            var ids=new TreeSet<UUID>(Comparator.comparing(UUID::toString));ids.add(hint.mainVehicle().id());if(hint.trailer()!=null)ids.add(hint.trailer().id());for(UUID id:ids)vehicles.lockCurrentForDispatch(id);
            var source=tasks.lockCurrentForDispatch(hint.taskId());var anchor=dispatch.anchor(hint.taskId(),true);var current=dispatch.assignment(hint.id(),true);
            if(!current.status().equals("ASSIGNED")||dispatch.current(hint.taskId()).filter(value->value.id().equals(hint.id())).isEmpty()
                    ||!source.accepted()||source.source().orderVersion()!=anchor.sourceOrderVersion()||!source.source().equals(current.source())||!state.source().equals(current.source()))throw state();
            access.completeOccupation(current);
        }catch(TmsFailure|ExecutionFailure|UpmsException failure){if(failure instanceof UpmsException upms&&upms.status()!=404)throw failure;throw state();}
    }
    private boolean isCurrent(ExceptionStore.State state) {
        try {
            var assignment=dispatch.assignment(state.value().assignmentId(),false);var source=tasks.find(state.value().taskId());
            return assignment.status().equals("ASSIGNED")&&dispatch.current(assignment.taskId()).filter(value->value.id().equals(assignment.id())).isPresent()
                    &&source.isPresent()&&source.get().accepted()&&source.get().source().equals(state.source())
                    &&assignment.reservations().size()==(assignment.trailer()==null?2:3)&&assignment.reservations().stream().allMatch(value->value.releasedAt()==null);
        }catch(TmsFailure|UpmsException missing){if(missing instanceof UpmsException upms&&upms.status()!=404)throw missing;return false;}
    }
    private ExceptionDtos.Detail detail(ExceptionStore.State state,boolean management,boolean current) {
        var view=execution.view(state.value().assignmentId());var context=new ExceptionPolicy.Context(state.source(),state.driver(),view);
        boolean baseConfirmed=confirmed(context,state.policyMode(),true);
        var availableRule=baseConfirmed?policy.requirements(context).filter(ExceptionCaseService::validRule):Optional.<ExecutionEvidencePolicy.Requirements>empty();
        boolean confirmed=baseConfirmed&&availableRule.isPresent();
        boolean mutable=state.value().version()<TmsValidation.MAX;
        var forbidden=new ExceptionDtos.Availability(false,"FORBIDDEN");
        var request=management?availability(current,confirmed&&policy.allows(ExceptionPolicy.Action.REQUEST_SUPPLEMENT,context),mutable&&state.value().status()==ExceptionStatus.OPEN,null):forbidden;
        var resolve=management?availability(current,confirmed&&policy.allows(ExceptionPolicy.Action.RESOLVE,context),mutable&&state.value().status()==ExceptionStatus.OPEN,cases.submittedEvidence(state.value().id()).isEmpty()?"SUPPLEMENT_REQUIRED":null):forbidden;
        var upload=management?forbidden:availability(current,confirmed&&policy.allows(ExceptionPolicy.Action.UPLOAD_SUPPLEMENT,context),mutable&&state.value().status()==ExceptionStatus.NEEDS_SUPPLEMENT,state.value().activeRequest()!=null&&state.value().activeRequest().uploadedCount()>=32?"INVALID_STATE":null);
        var submit=management?forbidden:availability(current,confirmed&&policy.allows(ExceptionPolicy.Action.SUBMIT_SUPPLEMENT,context),mutable&&state.value().status()==ExceptionStatus.NEEDS_SUPPLEMENT,state.value().activeRequest()!=null&&state.value().activeRequest().uploadedCount()==0?"EVIDENCE_REQUIRED":null);
        ExecutionEvidencePolicy.Requirements rule=null;
        if(current&&confirmed&&state.value().activeRequest()!=null)rule=availableRule.orElse(null);
        return new ExceptionDtos.Detail(state.value(),view,new ExceptionDtos.Actions(confirmed?PolicyMode.SYNTHETIC_TEST_ONLY:PolicyMode.UNCONFIRMED,request,resolve,upload,submit,rule));
    }
    private static ExceptionDtos.Availability availability(boolean current,boolean confirmed,boolean state,String blocker){String why=!current?"INVALID_STATE":!state?"INVALID_STATE":!confirmed?"POLICY_NOT_CONFIRMED":blocker;return new ExceptionDtos.Availability(why==null,why);}
    private ExceptionPolicy.Context context(ExceptionStore.State state){return new ExceptionPolicy.Context(state.source(),state.driver(),execution.view(state.value().assignmentId()));}
    private boolean confirmed(ExceptionPolicy.Context context,PolicyMode recorded,boolean provider) {
        return policy.mode()==PolicyMode.SYNTHETIC_TEST_ONLY&&dispatchPolicy.mode()==PolicyMode.SYNTHETIC_TEST_ONLY&&recorded==PolicyMode.SYNTHETIC_TEST_ONLY
                &&(!provider||storage.mode()==PolicyMode.SYNTHETIC_TEST_ONLY);
    }
    private void requirePolicy(ExceptionPolicy.Context context,PolicyMode recorded,ExceptionPolicy.Action action,boolean provider){if(!confirmed(context,recorded,provider)||!policy.allows(action,context))throw policy();}
    private ExecutionEvidencePolicy.Requirements rule(ExceptionPolicy.Context context){var rule=policy.requirements(context).filter(ExceptionCaseService::validRule);return rule.orElseThrow(ExceptionCaseService::policy);}
    private static boolean validRule(ExecutionEvidencePolicy.Requirements rule){return rule.minimumCount()>0&&rule.minimumCount()<=32&&rule.allowedMediaTypes().equals(List.of("image/png"))&&rule.maxBytes()>0&&rule.maxBytes()<=EvidenceValidation.HARD_CAP&&rule.maxPixels()>0&&rule.maxPixels()<=EvidenceValidation.HARD_CAP;}
    private void verify(ExceptionStore.State state,UUID request,List<UUID> ids,ExecutionEvidencePolicy.Requirements rule) {
        for(UUID id:ids){if(request!=null&&!cases.linked(state.value().id(),request,id))throw invalidEvidence();if(request==null&&!cases.belongs(state.value().id(),id))throw invalidEvidence();
            var stored=evidence.find(state.value().assignmentId(),id).orElseThrow(ExceptionCaseService::invalidEvidence);
            if(!stored.driverId().equals(state.driverId())||!stored.evidence().taskId().equals(state.value().taskId())||!stored.evidence().status().equals("VERIFIED"))throw invalidEvidence();
            var bytes=storage.load(stored.objectId());if(!integrity(stored.evidence(),bytes))throw invalidEvidence();try{PngEvidenceValidator.validate(bytes,rule);}catch(EvidenceFailure invalid){throw invalidEvidence();}
        }
    }
    private static void validatePng(byte[] bytes,ExecutionEvidencePolicy.Requirements rule){try{PngEvidenceValidator.validate(bytes,rule);}catch(EvidenceFailure failure){throw new ExceptionFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}}
    private static boolean integrity(EvidenceDtos.Evidence metadata,byte[] bytes){return bytes!=null&&metadata.byteSize()==bytes.length&&EvidenceValidation.digest(bytes).equals(metadata.sha256());}
    private static void checkReceipt(ExceptionStore.Receipt old,UUID id,UUID assignment,UUID driver,String hash){if(id!=null&&!old.exceptionId().equals(id)||!old.assignmentId().equals(assignment)||!old.driverId().equals(driver))throw ExceptionFailure.missing();if(!old.hash().equals(hash))throw ExceptionFailure.conflict("IDEMPOTENCY_KEY_REUSED","此重送識別已用於不同內容");}
    private void key(UUID actor,String path,String key){if(!dispatch.tryKey(actor,path,key))throw ExceptionFailure.conflict("IDEMPOTENCY_IN_PROGRESS","相同重送識別仍在處理");}
    private static void exceptionVersion(ExceptionStore.State state,long expected){if(state.value().version()!=expected)throw ExceptionFailure.version(state.value().version(),false);if(expected>=TmsValidation.MAX)throw state();}
    private static void executionVersion(ExecutionDtos.View view,long expected){if(view.version()!=expected)throw ExceptionFailure.version(view.version(),true);}
    private static void active(ExceptionStore.State state,UUID request){if(state.value().status()!=ExceptionStatus.NEEDS_SUPPLEMENT||state.value().activeRequest()==null||!state.value().activeRequest().id().equals(request))throw state();}
    private static boolean reported(ExecutionPhase phase){return phase==ExecutionPhase.DEPARTED||phase==ExecutionPhase.IN_PROGRESS||phase==ExecutionPhase.AWAITING_COMPLETION;}
    private static ExceptionFailure state(){return ExceptionFailure.conflict("INVALID_STATE","目前異常或指派狀態不能執行此操作");}
    private static ExceptionFailure policy(){return ExceptionFailure.conflict("POLICY_NOT_CONFIRMED","尚無已核准的異常處置及補件政策");}
    private static ExceptionFailure supplement(){return ExceptionFailure.conflict("SUPPLEMENT_REQUIRED","尚需有效且明確提交的補件");}
    private static ExceptionFailure invalidEvidence(){return ExceptionFailure.conflict("EVIDENCE_INVALID","所選證據不符合本次補件要求");}
    private static String self(UUID assignment){return "/api/v1/tms/my-tasks/"+assignment;}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private static String uploadHash(String path,long version,long executionVersion,String media,byte[] bytes){var node=JsonMapper.builder().build().createObjectNode().put("path",path).put("method","POST").put("expectedVersion",version).put("expectedExecutionVersion",executionVersion).put("mediaType",media).put("byteSize",bytes.length).put("sha256",EvidenceValidation.digest(bytes));return TmsValidation.hash(node);}
}
