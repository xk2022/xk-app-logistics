package com.xksystem.logistics.tms.internal;
import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.tms.execution.ExecutionDtos;
import com.xksystem.logistics.tom.closure.*;
import com.xksystem.logistics.upms.AccountDto;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
@Repository
class ManagementConfirmationStore {
 record State(UUID completionId,TmsDtos.Assignment assignment,long sourceVersion,com.xksystem.logistics.tms.completion.CompletionDtos.Witness completion,ExecutionDtos.View execution,ClosureDtos.ConfirmationFact fact){}
 record Receipt(String hash,ClosureDtos.ConfirmationResult data){}
 private final JdbcTemplate jdbc;private final JsonMapper json;private final TmsStore dispatch;private final CompletionStore completions;private final ExecutionStore execution;
 ManagementConfirmationStore(JdbcTemplate jdbc,JsonMapper json,TmsStore dispatch,CompletionStore completions,ExecutionStore execution){this.jdbc=jdbc;this.json=json;this.dispatch=dispatch;this.completions=completions;this.execution=execution;}
 State state(UUID id){var rows=jdbc.queryForList("SELECT w.assignment_id,w.source_order_version FROM logistics.tms_completion w JOIN logistics.tms_task_dispatch t ON t.task_id=w.task_id WHERE w.id=? AND t.company_scope=?",id,TmsStore.SCOPE);if(rows.isEmpty())throw ClosureFailure.missing();var row=rows.getFirst();var a=dispatch.assignment((UUID)row.get("assignment_id"),false);var w=completions.witness(a.id()).orElseThrow(ClosureFailure::corrupt);completions.validate(w);
  var facts=jdbc.queryForList("SELECT * FROM logistics.tms_completion_confirmation WHERE completion_id=?",id);ClosureDtos.ConfirmationFact fact=facts.isEmpty()?null:fact(facts.getFirst());if(fact!=null)validate(fact.id());
  return new State(id,a,((Number)row.get("source_order_version")).longValue(),w,execution.view(a.id()),fact);
 }
 void validate(UUID id){try{jdbc.queryForList("SELECT logistics.validate_management_confirmation(?)",id);}catch(org.springframework.dao.DataAccessException failure){throw ClosureFailure.corrupt();}}
 ClosureDtos.ConfirmationSummary summary(State s){var a=s.assignment();return new ClosureDtos.ConfirmationSummary(s.completionId(),a.id(),a.taskId(),a.source().taskNo(),a.orderId(),a.source().orderNo(),s.sourceVersion(),s.completion().completedAt(),s.fact()==null?0:1,s.fact()==null?"PENDING":"CONFIRMED",s.fact()==null?null:s.fact().confirmedAt());}
 TmsDtos.Page<ClosureDtos.ConfirmationSummary> page(ClosureValidation.Query q){var args=new ArrayList<Object>();args.add(TmsStore.SCOPE);String where=" FROM logistics.tms_completion w JOIN logistics.tms_task_dispatch t ON t.task_id=w.task_id LEFT JOIN logistics.tms_completion_confirmation f ON f.completion_id=w.id WHERE t.company_scope=?";if(q.orderId()!=null){where+=" AND w.order_id=?";args.add(q.orderId());}if(!q.state().equals("ALL"))where+=q.state().equals("PENDING")?" AND f.id IS NULL":" AND f.id IS NOT NULL";long n=jdbc.queryForObject("SELECT count(*)"+where,Long.class,args.toArray());args.add(q.size());args.add((long)q.page()*q.size());var ids=jdbc.query("SELECT w.id"+where+" ORDER BY w.completed_at DESC,w.id LIMIT ? OFFSET ?",(r,i)->r.getObject(1,UUID.class),args.toArray());return TmsDtos.Page.of(ids.stream().map(id->summary(state(id))).toList(),q.page(),q.size(),n);}
 Optional<Receipt> receipt(UUID actor,String path,String key){var rows=jdbc.queryForList("SELECT request_hash,original_data::text FROM logistics.tms_completion_confirmation_command WHERE company_scope=? AND actor_id=? AND http_method='POST' AND canonical_path=? AND idempotency_key=?",TmsStore.SCOPE,actor,path,key);return rows.isEmpty()?Optional.empty():Optional.of(new Receipt(rows.getFirst().get("request_hash").toString(),ManagementReceiptCodec.confirmation(rows.getFirst().get("original_data").toString())));}
 void insert(State s,AccountDto actor,ClosureDtos.ConfirmationResult result,String path,String key,String hash){UUID receipt=UUID.randomUUID();var f=result.confirmation();var at=OffsetDateTime.ofInstant(f.confirmedAt(),ZoneOffset.UTC);
  jdbc.update("INSERT INTO logistics.tms_completion_confirmation(id,completion_id,assignment_id,task_id,order_id,source_order_version,execution_version,version,actor_id,actor_role,actor_display_name,confirmed_at,reason,policy_mode,policy_snapshot,receipt_id) VALUES (?,?,?,?,?,?,?,1,?,?,?,?,?,'SYNTHETIC_TEST_ONLY',?::json,?)",f.id(),s.completionId(),s.assignment().id(),s.assignment().taskId(),s.assignment().orderId(),s.sourceVersion(),s.execution().version(),actor.id(),actor.role().name(),actor.displayName().getBytes(StandardCharsets.UTF_8),at,f.reason().getBytes(StandardCharsets.UTF_8),"{\"administrativeOnly\":true}",receipt);
  jdbc.update("INSERT INTO logistics.tms_completion_confirmation_command(id,confirmation_id,completion_id,company_scope,actor_id,http_method,canonical_path,idempotency_key,request_hash,original_status,original_data,created_at) VALUES (?,?,?,?,?,'POST',?,?,?,200,?::json,?)",receipt,f.id(),s.completionId(),TmsStore.SCOPE,actor.id(),path,key,hash,ManagementReceiptCodec.confirmation(result),at);
 }
 private ClosureDtos.ConfirmationFact fact(Map<String,Object> row){return new ClosureDtos.ConfirmationFact((UUID)row.get("id"),1,((Timestamp)row.get("confirmed_at")).toInstant(),PolicyMode.valueOf(row.get("policy_mode").toString()),decode(row.get("reason")),new ClosureDtos.Actor(row.get("actor_role").toString(),decode(row.get("actor_display_name"))));}
 private static String decode(Object bytes){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap((byte[])bytes)).toString();}catch(java.nio.charset.CharacterCodingException failure){throw ClosureFailure.corrupt();}}
}
