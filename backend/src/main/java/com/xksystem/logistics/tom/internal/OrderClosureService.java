package com.xksystem.logistics.tom.internal;
import com.xksystem.logistics.tom.*;
import com.xksystem.logistics.tom.closure.*;
import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.upms.*;
import com.xksystem.logistics.upms.security.*;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional
public class OrderClosureService {
 private final OrderClosureStore store;private final OrderReviewQueries sources;private final OrderClosureFacts facts;private final OrderClosurePolicy policy;private final UpmsAuthorization auth;private final Clock clock;
 public OrderClosureService(OrderClosureStore store,OrderReviewQueries sources,OrderClosureFacts facts,OrderClosurePolicy policy,UpmsAuthorization auth,Clock clock){this.store=store;this.sources=sources;this.facts=facts;this.policy=policy;this.auth=auth;this.clock=clock;}
 public record Written(ClosureDtos.Result data,boolean replayed){}
 private AccountDto manager(SessionPrincipal p,HttpSession session){var actor=auth.requireOrderManagerForWrite(p);UpmsSessions.requireCurrent(session,p,clock);return actor;}
 public TmsDtos.Page<ClosureDtos.Summary> worklist(SessionPrincipal p,ClosureValidation.Query q,HttpSession session){manager(p,session);return store.page(q);}
 public ClosureDtos.View detail(SessionPrincipal p,UUID id,HttpSession session){manager(p,session);var source=sources.lockForReview(id);var witness=store.witness(id).orElse(null);String audit=store.audit(source,witness);Map<UUID,OrderClosureFacts.TaskFact> proof=Map.of();OrderClosureFacts.Totals totals=null;ClosureDtos.Counts counts=null;
  if(source.lifecycleStatus().equals("OPEN")&&source.acceptanceStatus().equals("ACCEPTED")){totals=facts.lockOrder(id);proof=collect(source);counts=new ClosureDtos.Counts(source.tasks().size(),proof.values().stream().filter(OrderClosureFacts.TaskFact::verified).count(),proof.values().stream().filter(f->f.verified()&&f.confirmationVersion()==1).count(),totals.blockingExceptionCount(),totals.effectiveOccupationCount(),source.version());}
  var projection=projection(source,witness,proof,totals);return new ClosureDtos.View(id,source.orderNo(),source.lifecycleStatus(),source.acceptanceStatus(),source.version(),audit,witness,projection,counts);
 }
 public TmsDtos.Page<ClosureDtos.TaskReview> tasks(SessionPrincipal p,UUID id,ClosureValidation.Query q,HttpSession session){manager(p,session);var source=sources.lockForReview(id);var w=store.witness(id).orElse(null);String audit=store.audit(source,w);if(audit.equals("RECORDED"))return store.recordedTasks(id,w,q);
  if(!source.lifecycleStatus().equals("OPEN")||!source.acceptanceStatus().equals("ACCEPTED"))throw ClosureFailure.conflict("INVALID_STATE","此狀態不提供結案任務證明");facts.lockOrder(id);var proof=collect(source);var sorted=source.tasks().stream().sorted(Comparator.comparing(TaskQueries.Source::taskNo).thenComparing(t->t.taskId().toString())).toList();long from=(long)q.page()*q.size();var selected=from>=sorted.size()?List.<TaskQueries.Source>of():sorted.subList((int)from,Math.min(sorted.size(),(int)from+q.size()));
  return TmsDtos.Page.of(selected.stream().map(t->{var f=proof.get(t.taskId());if(!f.sourceMatches())throw ClosureFailure.corrupt();return new ClosureDtos.TaskReview(id,source.version(),t.taskId(),t.taskNo(),t.requiredTask(),f.assignmentId(),f.completionId(),f.executionPhase(),f.executionVersion(),f.verified(),f.confirmationVersion(),f.confirmedAt(),f.blockingExceptionCount(),f.effectiveOccupationCount());}).toList(),q.page(),q.size(),sorted.size());
 }
 public TmsDtos.Page<ClosureDtos.Witness> history(SessionPrincipal p,UUID id,ClosureValidation.Query q,HttpSession session){manager(p,session);var source=sources.find(id);var witness=store.witness(id).orElse(null);store.audit(source,witness);return TmsDtos.Page.of(witness==null||q.page()!=0?List.of():List.of(witness),q.page(),q.size(),witness==null?0:1);}
 public Written close(SessionPrincipal p,UUID id,byte[] raw,String submittedKey,String trace,HttpSession session){var preliminary=auth.requireOrderManager(p);var input=ClosureValidation.command(raw,false);String key=ClosureValidation.key(submittedKey),path="/api/v1/orders/"+id+"/close";
  if(!store.key(preliminary.id(),path,key))throw ClosureFailure.conflict("IDEMPOTENCY_IN_PROGRESS","相同重送識別仍在處理");var actor=manager(p,session);sources.find(id);var previous=store.receipt(actor.id(),path,key);
  if(previous.isPresent()){if(!previous.get().hash().equals(input.hash()))throw ClosureFailure.conflict("IDEMPOTENCY_KEY_REUSED","此識別已用於不同內容");var w=store.witness(id).orElseThrow(ClosureFailure::corrupt);store.validate(id,w);var saved=previous.get().data();var current=sources.find(id);if(!id.equals(saved.orderId())||!current.orderNo().equals(saved.orderNo())||!"CLOSED".equals(saved.lifecycleStatus())||!"ACCEPTED".equals(saved.acceptanceStatus())||saved.version()!=w.version()||!w.equals(saved.closure()))throw ClosureFailure.corrupt();return new Written(saved,true);}
  var source=sources.lockForReview(id);if(source.version()!=input.expectedVersion())throw ClosureFailure.version(source.version(),false);
  if(!source.lifecycleStatus().equals("OPEN")||!source.acceptanceStatus().equals("ACCEPTED")||source.version()>=TmsValidation.MAX)throw ClosureFailure.conflict("INVALID_STATE","目前訂單無法結案");var totals=facts.lockOrder(id);var proof=collect(source);var projected=projection(source,null,proof,totals);if(!projected.canClose()){
   String reason=projected.blocker();if(!Set.of("POLICY_NOT_CONFIRMED","TASKS_INCOMPLETE","CONFIRMATION_REQUIRED","UNRESOLVED_EXCEPTIONS","RESOURCES_OCCUPIED").contains(reason))reason="INVALID_STATE";throw ClosureFailure.conflict(reason,"目前來源、政策或完整結案條件尚未完成");}
  var w=new ClosureDtos.Witness(UUID.randomUUID(),source.version(),source.version()+1,clock.instant().truncatedTo(ChronoUnit.MICROS),PolicyMode.SYNTHETIC_TEST_ONLY,true,input.reason(),source.tasks().size(),new ClosureDtos.Actor(actor.role().name(),actor.displayName()));var result=new ClosureDtos.Result(id,source.orderNo(),"CLOSED","ACCEPTED",w.version(),w);store.close(source,proof,actor,result,input.reason(),path,key,input.hash(),trace);return new Written(result,false);
 }
 private Map<UUID,OrderClosureFacts.TaskFact> collect(OrderReviewQueries.Source source){var result=new LinkedHashMap<UUID,OrderClosureFacts.TaskFact>();for(int i=0;i<source.tasks().size();i+=100)result.putAll(facts.tasks(source.orderId(),source.version(),source.tasks().subList(i,Math.min(source.tasks().size(),i+100))));if(result.size()!=source.tasks().size())throw ClosureFailure.corrupt();return Map.copyOf(result);}
 private ClosureDtos.PolicyView projection(OrderReviewQueries.Source source,ClosureDtos.Witness witness,Map<UUID,OrderClosureFacts.TaskFact> proof,OrderClosureFacts.Totals totals){String blocker=null;
  if(source.lifecycleStatus().equals("CLOSED"))blocker="ALREADY_CLOSED";
  else if(policy.mode()!=PolicyMode.SYNTHETIC_TEST_ONLY)blocker="POLICY_NOT_CONFIRMED";
  else if(!source.lifecycleStatus().equals("OPEN"))blocker="ORDER_NOT_OPEN";
  else if(!source.acceptanceStatus().equals("ACCEPTED"))blocker="ORDER_NOT_ACCEPTED";
  else if(source.version()>=TmsValidation.MAX||!source.acceptedSourceIntact()||proof.values().stream().anyMatch(f->!f.sourceMatches()))blocker="PROOF_INVALID";
  else if(!policy.allows(source.tasks().stream().map(t->new OrderClosurePolicy.TaskContext(t.taskKind(),proof.get(t.taskId()).driverCode(),t.requiredTask())).toList()))blocker="POLICY_NOT_CONFIRMED";
  else if(source.tasks().isEmpty()||proof.values().stream().anyMatch(f->!f.verified()))blocker="TASKS_INCOMPLETE";
  else if(proof.values().stream().anyMatch(f->f.confirmationVersion()!=1))blocker="CONFIRMATION_REQUIRED";
  else if(totals.blockingExceptionCount()!=0)blocker="UNRESOLVED_EXCEPTIONS";
  else if(totals.effectiveOccupationCount()!=0)blocker="RESOURCES_OCCUPIED";
  return new ClosureDtos.PolicyView(policy.mode(),blocker==null,blocker,blocker==null?new ClosureDtos.Requirements("ALL_PERSISTED_TASKS",true,true,true):null);
 }
}
