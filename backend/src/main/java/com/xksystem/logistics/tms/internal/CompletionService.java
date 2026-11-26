package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.tms.DispatchEligibilityPolicy;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.TmsFailure;
import com.xksystem.logistics.tms.TmsValidation;
import com.xksystem.logistics.tms.completion.*;
import com.xksystem.logistics.tms.evidence.*;
import com.xksystem.logistics.tms.exceptions.ExceptionQueries;
import com.xksystem.logistics.tms.execution.*;
import com.xksystem.logistics.upms.UpmsAuthorization;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import com.xksystem.logistics.upms.security.UpmsSessions;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Completion owns the terminal witness; recorded legacy dispatch facts stay intact. */
@Service
public class CompletionService {
    private final TmsStore dispatch;private final ExecutionStore execution;private final CompletionStore completions;
    private final ExecutionService access;private final UpmsAuthorization authorization;private final DriverQueries drivers;
    private final CompletionPolicy policy;private final DispatchEligibilityPolicy dispatchPolicy;
    private final ExecutionEvidencePolicy evidencePolicy;private final EvidenceStorage storage;private final EvidenceStore evidence;
    private final ExceptionQueries exceptions;private final Clock clock;
    public CompletionService(TmsStore dispatch,ExecutionStore execution,CompletionStore completions,ExecutionService access,
            UpmsAuthorization authorization,DriverQueries drivers,CompletionPolicy policy,DispatchEligibilityPolicy dispatchPolicy,
            ExecutionEvidencePolicy evidencePolicy,EvidenceStorage storage,EvidenceStore evidence,ExceptionQueries exceptions,Clock clock){
        this.dispatch=dispatch;this.execution=execution;this.completions=completions;this.access=access;this.authorization=authorization;
        this.drivers=drivers;this.policy=policy;this.dispatchPolicy=dispatchPolicy;this.evidencePolicy=evidencePolicy;this.storage=storage;this.evidence=evidence;this.exceptions=exceptions;this.clock=clock;
    }
    public record Written(CompletionDtos.Result data,boolean replayed) {}
    private record Scope(ExecutionService.Owned owned,ExecutionDtos.View execution,CompletionDtos.Witness completion) {}
    @Transactional
    public CompletionDtos.Detail detail(SessionPrincipal principal,UUID assignmentId,HttpSession session){
        var scope=current(principal,assignmentId,session);var assignment=scope.owned().assignment();var s=assignment.source();
        return new CompletionDtos.Detail(assignmentId,s.taskId(),s.taskNo(),s.item(),s.taskKind(),s.pickupName(),s.pickupAddress(),s.deliveryName(),s.deliveryAddress(),s.siteContact(),s.plannedStartAt(),s.plannedEndAt(),assignment.assignedAt(),assignment.policyMode(),scope.execution(),scope.completion());
    }
    @Transactional
    public CompletionDtos.PolicyView policy(SessionPrincipal principal,UUID assignmentId,HttpSession session){
        var scope=current(principal,assignmentId,session);String blocker=null;CompletionPolicy.Requirements rule=null;List<UUID> ids=null;
        if(scope.completion()!=null)blocker="ALREADY_COMPLETED";
        else {
            var available=rule(scope.owned());
            if(available.isEmpty())blocker="POLICY_NOT_CONFIRMED";
            else if(scope.execution().phase()!=ExecutionPhase.AWAITING_COMPLETION||scope.execution().version()>=TmsValidation.MAX)blocker="UNSUPPORTED_PHASE";
            else if(exceptions.lockBlockingForAssignment(assignmentId).unresolvedCount()!=0)blocker="UNRESOLVED_EXCEPTIONS";
            else try{var proof=proof(scope.owned(),scope.execution(),available.get());rule=available.get();ids=proof.evidenceIds();}
            catch(CompletionFailure failure){if(failure.code().equals("E2007"))blocker="EVIDENCE_REQUIRED";else if(failure.code().equals("E2008"))blocker="EVIDENCE_INVALID";else throw failure;}
        }
        return new CompletionDtos.PolicyView(assignmentId,scope.owned().assignment().taskId(),scope.execution(),policy.mode(),blocker==null,blocker,rule,ids);
    }
    @Transactional
    public Written complete(SessionPrincipal principal,UUID assignmentId,byte[] raw,String submittedKey,HttpSession session){
        var preliminary=authorization.requireDriverSelf(principal);var input=CompletionValidation.command(raw);String key=CompletionValidation.key(submittedKey);
        String path="/api/v1/tms/my-tasks/"+assignmentId+"/complete";
        if(!dispatch.tryKey(preliminary.id(),path,key))throw CompletionFailure.conflict("IDEMPOTENCY_IN_PROGRESS","相同重送識別仍在處理");
        var scope=current(principal,assignmentId,session);var owned=scope.owned();var previous=completions.receipt(owned.actorId(),path,key);
        if(previous.isPresent()){
            var prior=previous.get();if(!prior.driverId().equals(owned.driverId())||!prior.result().assignmentId().equals(assignmentId)||!prior.result().taskId().equals(owned.assignment().taskId()))throw CompletionFailure.missing();
            if(!prior.hash().equals(input.hash()))throw CompletionFailure.conflict("IDEMPOTENCY_KEY_REUSED","此重送識別已用於不同內容");
            if(scope.completion()==null||!prior.result().completion().equals(scope.completion()))throw new IllegalStateException("Completion receipt is not witnessed");
            return new Written(prior.result(),true);
        }
        // These explicit settlement failures are reachable only after this actor's successful ledger MISS.
        if(scope.execution().version()!=input.expectedVersion())throw CompletionFailure.version(scope.execution().version());
        if(scope.execution().phase()!=ExecutionPhase.AWAITING_COMPLETION||scope.execution().version()>=TmsValidation.MAX)throw CompletionFailure.conflict("INVALID_STATE","目前階段不能完成");
        var rule=rule(owned).orElseThrow(()->CompletionFailure.conflict("POLICY_NOT_CONFIRMED","尚無已核准的完成及安全釋放政策"));
        if(exceptions.lockBlockingForAssignment(assignmentId).unresolvedCount()!=0)throw CompletionFailure.conflict("UNRESOLVED_EXCEPTIONS","仍有未處置完成的異常");
        var proof=proof(owned,scope.execution(),rule);
        if(input.evidenceIds().isEmpty())throw CompletionFailure.conflict("EVIDENCE_REQUIRED","尚缺必要的完成證據");
        if(!input.evidenceIds().equals(proof.evidenceIds()))throw invalid();
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(now.isBefore(scope.execution().departedAt()))throw new IllegalStateException("Completion clock precedes departure");
        var witness=new CompletionDtos.Witness(UUID.randomUUID(),now,now,PolicyMode.SYNTHETIC_TEST_ONLY,true);
        var result=new CompletionDtos.Result(assignmentId,owned.assignment().taskId(),new ExecutionDtos.View(ExecutionPhase.COMPLETED,scope.execution().version()+1,scope.execution().departedAt()),witness);
        completions.complete(owned,scope.execution(),result,proof,rule,path,key,input.hash());return new Written(result,false);
    }
    private Scope current(SessionPrincipal principal,UUID assignmentId,HttpSession session){
        var actor=authorization.requireDriverSelfWithLocks(principal);UpmsSessions.requireCurrent(session,principal,clock);
        var driver=drivers.currentBoundDriverWithLock(actor.id()).orElseThrow(CompletionFailure::missing);
        try{
            var hint=dispatch.assignment(assignmentId,false);
            if(!hint.status().equals("ASSIGNED")||!hint.driver().id().equals(driver.id()))throw CompletionFailure.missing();
            var view=execution.view(assignmentId);var witness=completions.witness(assignmentId);
            if(view.phase()!=ExecutionPhase.COMPLETED&&witness.isEmpty()){
                try{var owned=access.current(principal,assignmentId,session);return new Scope(owned,execution.view(assignmentId),null);}
                catch(ExecutionFailure failure){if(failure.status()==404)throw CompletionFailure.missing();throw failure;}
            }
            // This path has no FMS availability, live occupation or current/open TOM grant.
            var anchor=dispatch.anchor(hint.taskId(),true);var assignment=dispatch.assignment(assignmentId,true);view=execution.view(assignmentId);
            if(!assignment.status().equals("ASSIGNED")||!assignment.driver().id().equals(driver.id())
                    ||dispatch.current(assignment.taskId()).filter(value->value.id().equals(assignmentId)).isEmpty()
                    ||!anchor.orderId().equals(assignment.orderId())||anchor.sourceOrderVersion()!=assignment.source().orderVersion())throw CompletionFailure.missing();
            if(view.phase()!=ExecutionPhase.COMPLETED||witness.isEmpty())throw new IllegalStateException("Terminal completion fact is incomplete");
            completions.validate(witness.get());
            return new Scope(new ExecutionService.Owned(actor.id(),driver.id(),driver,assignment,actor),view,witness.get());
        }catch(TmsFailure failure){if(failure.status()==404)throw CompletionFailure.missing();throw failure;}
    }
    private Optional<CompletionPolicy.Requirements> rule(ExecutionService.Owned owned){
        if(policy.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY||dispatchPolicy.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY
                ||owned.assignment().policyMode()!=PolicyMode.SYNTHETIC_TEST_ONLY||evidencePolicy.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY
                ||storage.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY||!evidencePolicy.canRead(owned.assignment().source(),owned.driver()))return Optional.empty();
        var evidenceRule=evidencePolicy.requirements(owned.assignment().source(),owned.driver(),ExecutionPhase.IN_PROGRESS);
        if(evidenceRule.isEmpty()||!valid(evidenceRule.get()))return Optional.empty();
        return policy.requirements(owned.assignment().source(),owned.driver()).filter(value->
            "LATEST_AWAITING_TRANSITION".equals(value.evidenceBasis())&&value.requiresSafeReleaseConfirmation()
            &&valid(new ExecutionEvidencePolicy.Requirements(value.minimumCount(),value.allowedMediaTypes(),value.maxBytes(),value.maxPixels())));
    }
    private CompletionStore.Proof proof(ExecutionService.Owned owned,ExecutionDtos.View view,CompletionPolicy.Requirements rule){
        var proof=completions.proof(owned.assignment().id(),view.version()).orElseThrow(()->CompletionFailure.conflict("EVIDENCE_REQUIRED","尚缺原進度轉換的必要證據"));
        if(proof.evidenceIds().size()<rule.minimumCount())throw CompletionFailure.conflict("EVIDENCE_REQUIRED","尚缺必要的完成證據");
        var check=new ExecutionEvidencePolicy.Requirements(rule.minimumCount(),rule.allowedMediaTypes(),rule.maxBytes(),rule.maxPixels());
        var original=evidencePolicy.requirements(owned.assignment().source(),owned.driver(),ExecutionPhase.IN_PROGRESS).filter(CompletionService::valid).orElseThrow(()->CompletionFailure.conflict("POLICY_NOT_CONFIRMED","尚無已核准的證據驗證政策"));
        if(proof.evidenceIds().size()<original.minimumCount())throw CompletionFailure.conflict("EVIDENCE_REQUIRED","尚缺目前證據政策要求的照片");
        for(UUID id:proof.evidenceIds()){
            var stored=evidence.find(owned.assignment().id(),id).orElseThrow(CompletionService::invalid);var item=stored.evidence();
            if(!stored.driverId().equals(owned.driverId())||!item.taskId().equals(owned.assignment().taskId())||item.phase()!=ExecutionPhase.IN_PROGRESS||item.executionVersion()!=view.version()-1||!item.status().equals("VERIFIED")||!item.mediaType().equals("image/png"))throw invalid();
            var bytes=storage.load(stored.objectId());if(bytes==null||bytes.length!=item.byteSize()||!EvidenceValidation.digest(bytes).equals(item.sha256()))throw invalid();
            try{PngEvidenceValidator.validate(bytes,check);PngEvidenceValidator.validate(bytes,original);}catch(EvidenceFailure failure){throw invalid();}
        }
        return proof;
    }
    private static boolean valid(ExecutionEvidencePolicy.Requirements rule){return rule.minimumCount()>0&&rule.minimumCount()<=32&&rule.allowedMediaTypes().equals(List.of("image/png"))&&rule.maxBytes()>0&&rule.maxBytes()<=EvidenceValidation.HARD_CAP&&rule.maxPixels()>0&&rule.maxPixels()<=EvidenceValidation.HARD_CAP;}
    private static CompletionFailure invalid(){return CompletionFailure.conflict("EVIDENCE_INVALID","必要證據無法驗證");}
}
