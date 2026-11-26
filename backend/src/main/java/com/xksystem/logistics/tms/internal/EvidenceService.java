package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.tms.evidence.*;
import com.xksystem.logistics.tms.execution.*;
import com.xksystem.logistics.upms.UpmsAuthorization;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EvidenceService {
    private final ExecutionService access;private final ExecutionStore execution;private final EvidenceStore evidence;private final TmsStore dispatch;
    private final UpmsAuthorization authorization;private final ExecutionEvidencePolicy policy;private final EvidenceStorage storage;
    private final DispatchEligibilityPolicy dispatchPolicy;private final Clock clock;
    public EvidenceService(ExecutionService access,ExecutionStore execution,EvidenceStore evidence,TmsStore dispatch,UpmsAuthorization authorization,
            ExecutionEvidencePolicy policy,EvidenceStorage storage,DispatchEligibilityPolicy dispatchPolicy,Clock clock) {
        this.access=access;this.execution=execution;this.evidence=evidence;this.dispatch=dispatch;this.authorization=authorization;
        this.policy=policy;this.storage=storage;this.dispatchPolicy=dispatchPolicy;this.clock=clock;
    }
    public record Written<T>(T data,boolean replayed) {}
    public EvidenceDtos.PolicyView policy(SessionPrincipal principal,UUID assignment,HttpSession session) {
        var owned=current(principal,assignment,session);var view=execution.view(assignment);
        if(!enabled(owned))return new EvidenceDtos.PolicyView(assignment,owned.assignment().taskId(),view,PolicyMode.UNCONFIRMED,null,null,"POLICY_NOT_CONFIRMED");
        var next=next(view.phase());
        if(next==null)return new EvidenceDtos.PolicyView(assignment,owned.assignment().taskId(),view,PolicyMode.SYNTHETIC_TEST_ONLY,null,null,"UNSUPPORTED_PHASE");
        var candidate=policy.requirements(owned.assignment().source(),owned.driver(),view.phase()).filter(EvidenceService::validRule);
        if(candidate.isEmpty())return new EvidenceDtos.PolicyView(assignment,owned.assignment().taskId(),view,PolicyMode.UNCONFIRMED,null,null,"POLICY_NOT_CONFIRMED");
        var rule=candidate.get();
        return new EvidenceDtos.PolicyView(assignment,owned.assignment().taskId(),view,PolicyMode.SYNTHETIC_TEST_ONLY,next,rule,null);
    }
    public TmsDtos.Page<EvidenceDtos.Evidence> list(SessionPrincipal principal,UUID assignment,EvidenceValidation.Page page,HttpSession session) {
        current(principal,assignment,session);return evidence.page(assignment,page);
    }
    public byte[] content(SessionPrincipal principal,UUID assignment,UUID id,HttpSession session) {
        var owned=current(principal,assignment,session);
        var stored=evidence.find(assignment,id).orElseThrow(EvidenceFailure::missing);
        if(!stored.driverId().equals(owned.driverId())||!stored.evidence().taskId().equals(owned.assignment().taskId()))throw EvidenceFailure.missing();
        if(!readEnabled(owned))throw EvidenceFailure.conflict("POLICY_NOT_CONFIRMED","尚無已核准的照片讀取政策及私有儲存");
        byte[] bytes=storage.load(stored.objectId());
        if(!integrity(stored.evidence(),bytes))throw new IllegalStateException("Private evidence integrity unavailable");
        return bytes;
    }
    public Written<EvidenceDtos.Evidence> upload(SessionPrincipal principal,UUID assignment,long expectedVersion,String mediaType,byte[] bytes,String submittedKey,HttpSession session) {
        var actor=authorization.requireDriverSelf(principal);String key=EvidenceValidation.key(submittedKey),path="/api/v1/tms/my-tasks/"+assignment+"/evidence";
        String hash=EvidenceValidation.uploadHash(path,expectedVersion,mediaType,bytes);
        key(actor.id(),path,key);var owned=current(principal,assignment,session);
        var previous=evidence.receipt(owned.actorId(),path,key);
        if(previous.isPresent()) {
            var old=previous.get();if(!old.driverId().equals(owned.driverId())||!old.evidence().assignmentId().equals(assignment)||!old.evidence().taskId().equals(owned.assignment().taskId()))throw EvidenceFailure.missing();
            sameHash(old.hash(),hash);return new Written<>(old.evidence(),true);
        }
        var view=execution.view(assignment);version(view,expectedVersion);if(next(view.phase())==null)throw state();
        requireEnabled(owned);var rule=rule(owned,view);PngEvidenceValidator.validate(bytes,rule);
        UUID object=UUID.randomUUID();String sha=EvidenceValidation.digest(bytes);
        var result=new EvidenceDtos.Evidence(UUID.randomUUID(),assignment,owned.assignment().taskId(),view.phase(),view.version(),mediaType,bytes.length,sha,clock.instant().truncatedTo(ChronoUnit.MICROS),"VERIFIED");
        storage.store(object,bytes,sha);evidence.upload(owned,object,result,rule,path,key,hash);return new Written<>(result,false);
    }
    public Written<EvidenceDtos.ProgressResult> progress(SessionPrincipal principal,UUID assignment,byte[] raw,String submittedKey,HttpSession session) {
        var actor=authorization.requireDriverSelf(principal);var input=EvidenceValidation.progress(raw);String key=EvidenceValidation.key(submittedKey),path="/api/v1/tms/my-tasks/"+assignment+"/progress";
        key(actor.id(),path,key);var owned=current(principal,assignment,session);
        var previous=execution.receipt(owned.actorId(),path,key);
        if(previous.isPresent()) {
            var old=previous.get();if(!old.driverId().equals(owned.driverId())||!old.result().assignmentId().equals(assignment)||!old.result().taskId().equals(owned.assignment().taskId()))throw EvidenceFailure.missing();
            sameHash(old.hash(),input.hash());return new Written<>(new EvidenceDtos.ProgressResult(assignment,owned.assignment().taskId(),old.result().execution()),true);
        }
        var view=execution.view(assignment);version(view,input.expectedVersion());
        if(next(view.phase())!=input.targetPhase()||view.version()>=TmsValidation.MAX)throw state();
        requireEnabled(owned);var rule=rule(owned,view);
        for(UUID id:input.evidenceIds()) {
            var stored=evidence.find(assignment,id).orElseThrow(EvidenceService::invalidEvidence);var item=stored.evidence();
            if(!stored.driverId().equals(owned.driverId())||!item.taskId().equals(owned.assignment().taskId())||item.phase()!=view.phase()
                    ||item.executionVersion()!=view.version()||!item.status().equals("VERIFIED")||!item.mediaType().equals("image/png"))throw invalidEvidence();
            var bytes=storage.load(stored.objectId());if(!integrity(item,bytes))throw invalidEvidence();
            try{PngEvidenceValidator.validate(bytes,rule);}catch(EvidenceFailure invalid){throw invalidEvidence();}
        }
        if(input.evidenceIds().size()<rule.minimumCount())throw EvidenceFailure.conflict("EVIDENCE_REQUIRED","目前階段尚缺必要有效照片");
        var result=new EvidenceDtos.ProgressResult(assignment,owned.assignment().taskId(),new ExecutionDtos.View(input.targetPhase(),view.version()+1,view.departedAt()));
        evidence.progress(owned,view,result,input.evidenceIds(),path,key,input.hash(),clock.instant().truncatedTo(ChronoUnit.MICROS));return new Written<>(result,false);
    }
    private ExecutionService.Owned current(SessionPrincipal principal,UUID assignment,HttpSession session) {
        try{return access.current(principal,assignment,session);}catch(ExecutionFailure failure){if(failure.status()==404)throw EvidenceFailure.missing();throw failure;}
    }
    private boolean enabled(ExecutionService.Owned owned) {
        return readEnabled(owned)&&dispatchPolicy.mode()==PolicyMode.SYNTHETIC_TEST_ONLY&&owned.assignment().policyMode()==PolicyMode.SYNTHETIC_TEST_ONLY;
    }
    private boolean readEnabled(ExecutionService.Owned owned) {
        return policy.mode()==PolicyMode.SYNTHETIC_TEST_ONLY&&storage.mode()==PolicyMode.SYNTHETIC_TEST_ONLY&&policy.canRead(owned.assignment().source(),owned.driver());
    }
    private void requireEnabled(ExecutionService.Owned owned){if(!enabled(owned))throw EvidenceFailure.conflict("POLICY_NOT_CONFIRMED","尚無已核准的照片政策及私有儲存");}
    private ExecutionEvidencePolicy.Requirements rule(ExecutionService.Owned owned,ExecutionDtos.View view) {
        var rule=policy.requirements(owned.assignment().source(),owned.driver(),view.phase()).orElseThrow(()->EvidenceFailure.conflict("POLICY_NOT_CONFIRMED","尚無目前階段的核准照片規則"));
        if(!validRule(rule))
            throw EvidenceFailure.conflict("POLICY_NOT_CONFIRMED","目前照片規則尚未核准");
        return rule;
    }
    private static boolean validRule(ExecutionEvidencePolicy.Requirements rule) {
        // Technical allocation/command bounds, not a production evidence-policy default.
        return rule.minimumCount()>=1&&rule.minimumCount()<=32&&rule.allowedMediaTypes().equals(List.of("image/png"))
                &&rule.maxBytes()>0&&rule.maxBytes()<=EvidenceValidation.HARD_CAP
                &&rule.maxPixels()>0&&rule.maxPixels()<=EvidenceValidation.HARD_CAP;
    }
    private void key(UUID actor,String path,String key){if(!dispatch.tryKey(actor,path,key))throw EvidenceFailure.conflict("IDEMPOTENCY_IN_PROGRESS","相同重送識別仍在處理");}
    private static void sameHash(String first,String second){if(!first.equals(second))throw EvidenceFailure.conflict("IDEMPOTENCY_KEY_REUSED","此重送識別已用於不同內容");}
    private static void version(ExecutionDtos.View view,long expected){if(view.version()!=expected)throw EvidenceFailure.conflict("VERSION_CONFLICT","執行版本已更新，請重新讀取",view.version());}
    private static EvidenceFailure state(){return EvidenceFailure.conflict("INVALID_STATE","目前階段不能執行此操作");}
    private static EvidenceFailure invalidEvidence(){return EvidenceFailure.conflict("EVIDENCE_INVALID","所選證據不符合目前階段要求");}
    private static boolean integrity(EvidenceDtos.Evidence evidence,byte[] bytes){return bytes!=null&&bytes.length==evidence.byteSize()&&EvidenceValidation.digest(bytes).equals(evidence.sha256());}
    private static ExecutionPhase next(ExecutionPhase phase){return switch(phase){case DEPARTED->ExecutionPhase.IN_PROGRESS;case IN_PROGRESS->ExecutionPhase.AWAITING_COMPLETION;default->null;};}
}
