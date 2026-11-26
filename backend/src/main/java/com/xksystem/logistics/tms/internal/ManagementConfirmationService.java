package com.xksystem.logistics.tms.internal;
import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.tms.confirmation.ManagementCompletionPolicy;
import com.xksystem.logistics.tom.*;
import com.xksystem.logistics.tom.closure.*;
import com.xksystem.logistics.upms.*;
import com.xksystem.logistics.upms.security.*;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional
public class ManagementConfirmationService {
 private final ManagementConfirmationStore store;private final TmsStore dispatch;private final OrderReviewQueries sources;private final OrderClosureFacts facts;private final ManagementCompletionPolicy policy;private final UpmsAuthorization auth;private final Clock clock;
 public ManagementConfirmationService(ManagementConfirmationStore store,TmsStore dispatch,OrderReviewQueries sources,OrderClosureFacts facts,ManagementCompletionPolicy policy,UpmsAuthorization auth,Clock clock){this.store=store;this.dispatch=dispatch;this.sources=sources;this.facts=facts;this.policy=policy;this.auth=auth;this.clock=clock;}
 public record Written(ClosureDtos.ConfirmationResult data,boolean replayed){}
 private AccountDto manager(SessionPrincipal p,HttpSession session){var actor=auth.requireOrderManagerForWrite(p);UpmsSessions.requireCurrent(session,p,clock);return actor;}
 public TmsDtos.Page<ClosureDtos.ConfirmationSummary> list(SessionPrincipal p,ClosureValidation.Query q,HttpSession session){manager(p,session);return store.page(q);}
 public ClosureDtos.ConfirmationDetail detail(SessionPrincipal p,UUID id,HttpSession session){manager(p,session);var state=store.state(id);var source=sources.lockForReview(state.assignment().orderId());facts.lockOrder(source.orderId());String blocker=blocker(state,source);return new ClosureDtos.ConfirmationDetail(store.summary(state),state.execution(),state.completion(),state.fact(),new ClosureDtos.ConfirmationPolicyView(policy.mode(),blocker==null,blocker));}
 public TmsDtos.Page<ClosureDtos.ConfirmationFact> history(SessionPrincipal p,UUID id,ClosureValidation.Query q,HttpSession session){manager(p,session);var fact=store.state(id).fact();return TmsDtos.Page.of(fact==null||q.page()!=0?List.of():List.of(fact),q.page(),q.size(),fact==null?0:1);}
 public Written confirm(SessionPrincipal p,UUID id,byte[] raw,String submittedKey,HttpSession session){var preliminary=auth.requireOrderManager(p);var input=ClosureValidation.command(raw,true);String key=ClosureValidation.key(submittedKey),path="/api/v1/tms/completions/"+id+"/confirm";
  if(!dispatch.tryKey(preliminary.id(),path,key))throw ClosureFailure.conflict("IDEMPOTENCY_IN_PROGRESS","相同重送識別仍在處理");var actor=manager(p,session);var hint=store.state(id);var prior=store.receipt(actor.id(),path,key);
  if(prior.isPresent()){if(!prior.get().hash().equals(input.hash()))throw ClosureFailure.conflict("IDEMPOTENCY_KEY_REUSED","此識別已用於不同內容");var saved=prior.get().data();if(!id.equals(saved.completionId())||!hint.assignment().id().equals(saved.assignmentId())||!hint.assignment().taskId().equals(saved.taskId())||saved.sourceOrderVersion()!=hint.sourceVersion()||saved.version()!=1||hint.fact()==null||!hint.fact().equals(saved.confirmation()))throw ClosureFailure.corrupt();return new Written(saved,true);}
  var source=sources.lockForReview(hint.assignment().orderId());facts.lockOrder(source.orderId());var state=store.state(id);long actual=state.fact()==null?0:1;
  if(actual!=input.expectedVersion())throw ClosureFailure.version(actual,true);if(actual!=0)throw ClosureFailure.conflict("INVALID_STATE","此完成紀錄已行政確認");
  String blocker=blocker(state,source);if(blocker!=null){if(blocker.equals("POLICY_NOT_CONFIRMED"))throw ClosureFailure.conflict(blocker,"尚無核准的管理確認責任政策");if(blocker.equals("UNRESOLVED_EXCEPTIONS"))throw ClosureFailure.conflict(blocker,"仍有未處置的異常");throw ClosureFailure.conflict("INVALID_STATE","目前來源或完成證明無法確認");}
  var fact=new ClosureDtos.ConfirmationFact(UUID.randomUUID(),1,clock.instant().truncatedTo(ChronoUnit.MICROS),PolicyMode.SYNTHETIC_TEST_ONLY,input.reason(),new ClosureDtos.Actor(actor.role().name(),actor.displayName()));var result=new ClosureDtos.ConfirmationResult(id,state.assignment().id(),state.assignment().taskId(),state.sourceVersion(),1,fact);store.insert(state,actor,result,path,key,input.hash());return new Written(result,false);
 }
 private String blocker(ManagementConfirmationStore.State state,OrderReviewQueries.Source source){if(state.fact()!=null)return "ALREADY_CONFIRMED";if(policy.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY||!policy.allows(state.assignment().source(),state.assignment().driver()))return "POLICY_NOT_CONFIRMED";
  if(!source.lifecycleStatus().equals("OPEN")||!source.acceptanceStatus().equals("ACCEPTED"))return "ORDER_NOT_OPEN";if(source.version()!=state.sourceVersion())return "SOURCE_VERSION_MISMATCH";
  if(!source.acceptedSourceIntact()||source.tasks().stream().noneMatch(task->task.equals(state.assignment().source())))return "PROOF_INVALID";
  if(facts.totals(source.orderId()).blockingExceptionCount()!=0)return "UNRESOLVED_EXCEPTIONS";return null;
 }
}
