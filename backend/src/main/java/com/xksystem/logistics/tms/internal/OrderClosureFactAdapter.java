package com.xksystem.logistics.tms.internal;
import com.xksystem.logistics.tom.OrderClosureFacts;
import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.tom.closure.ClosureFailure;
import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
/** Query-only owned persistence; no callback into TOM/TMS services or FMS. */
@Component @Transactional(propagation=Propagation.MANDATORY)
public class OrderClosureFactAdapter implements OrderClosureFacts {
 private final JdbcTemplate jdbc;private final JsonMapper json;
 public OrderClosureFactAdapter(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
 public Totals lockOrder(UUID order){if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalTransactionStateException("Closure facts require writable caller transaction");
  jdbc.queryForList("SELECT task_id FROM logistics.tms_task_dispatch WHERE order_id=? AND company_scope=? ORDER BY task_id FOR UPDATE",order,TmsStore.SCOPE);
  jdbc.queryForList("SELECT id FROM logistics.tms_assignment WHERE order_id=? ORDER BY id FOR UPDATE",order);
  for(String table:List.of("tms_resource_reservation","tms_execution","tms_exception","tms_completion","tms_completion_confirmation")){
   String key=table.equals("tms_execution")?"assignment_id":"id";
   jdbc.queryForList("SELECT r."+key+" FROM logistics."+table+" r JOIN logistics.tms_assignment a ON a.id=r.assignment_id WHERE a.order_id=? ORDER BY r."+key+" FOR UPDATE OF r",order);
  }return totals(order);
 }
 public Totals totals(UUID order){return new Totals(jdbc.queryForObject("SELECT count(*) FROM logistics.tms_exception e JOIN logistics.tms_assignment a ON a.id=e.assignment_id JOIN logistics.tms_task_dispatch t ON t.task_id=a.task_id WHERE a.order_id=? AND t.company_scope=? AND e.status<>'RESOLVED'",Long.class,order,TmsStore.SCOPE),jdbc.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation r JOIN logistics.tms_assignment a ON a.id=r.assignment_id JOIN logistics.tms_task_dispatch t ON t.task_id=a.task_id WHERE a.order_id=? AND t.company_scope=? AND r.released_at IS NULL AND r.completion_id IS NULL",Long.class,order,TmsStore.SCOPE));}
 public Map<UUID,TaskFact> tasks(UUID order,long sourceVersion,List<TaskQueries.Source> sources){if(sources==null||sources.size()>100||sources.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("At most 100 exact source descriptors required");var result=new LinkedHashMap<UUID,TaskFact>();
  for(var source:sources){if(!source.orderId().equals(order)||source.orderVersion()!=sourceVersion)throw new IllegalArgumentException("Source identity mismatch");
   var rows=jdbc.queryForList("SELECT a.id,a.source_snapshot::text,a.driver_snapshot::text,x.phase,x.version,w.id AS completion_id,w.source_order_version,f.id AS confirmation_id,f.version AS confirmation_version,f.confirmed_at FROM logistics.tms_assignment a JOIN logistics.tms_task_dispatch t ON t.task_id=a.task_id LEFT JOIN logistics.tms_execution x ON x.assignment_id=a.id LEFT JOIN logistics.tms_completion w ON w.assignment_id=a.id LEFT JOIN logistics.tms_completion_confirmation f ON f.completion_id=w.id WHERE a.task_id=? AND a.order_id=? AND a.status='ASSIGNED' AND t.company_scope=?",source.taskId(),order,TmsStore.SCOPE);
   if(rows.isEmpty()){result.put(source.taskId(),new TaskFact(source.taskId(),null,null,ExecutionPhase.WAITING,0,false,true,null,0,null,null,blockers(source.taskId()),occupations(source.taskId())));continue;}
   var row=rows.getFirst();UUID assignment=(UUID)row.get("id"),completion=(UUID)row.get("completion_id"),confirmation=(UUID)row.get("confirmation_id");
   boolean matches=json.readValue(row.get("source_snapshot").toString(),TaskQueries.Source.class).equals(source);
   if(completion!=null)validate("validate_tms_completion",completion);if(confirmation!=null)validate("validate_management_confirmation",confirmation);
   var phase=row.get("phase")==null?ExecutionPhase.WAITING:ExecutionPhase.valueOf(row.get("phase").toString());long version=row.get("version")==null?0:((Number)row.get("version")).longValue();
   if(phase==ExecutionPhase.COMPLETED&&completion==null||completion!=null&&phase!=ExecutionPhase.COMPLETED)throw ClosureFailure.corrupt();
   var driver=json.readValue(row.get("driver_snapshot").toString(),TmsDtos.DriverSnapshot.class);
   result.put(source.taskId(),new TaskFact(source.taskId(),assignment,completion,phase,version,completion!=null&&matches&&((Number)row.get("source_order_version")).longValue()==sourceVersion,matches,confirmation,confirmation==null?0:1,row.get("confirmed_at")==null?null:((Timestamp)row.get("confirmed_at")).toInstant(),driver.driverCode(),blockers(source.taskId()),occupations(source.taskId())));
  }return Map.copyOf(result);
 }
 public Map<UUID,TaskFact> recorded(UUID order,long version,List<UUID> taskIds){if(taskIds==null||taskIds.size()>100||taskIds.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("At most 100 recorded task IDs required");var descriptors=new ArrayList<TaskQueries.Source>();
  for(UUID id:new LinkedHashSet<>(taskIds)){var rows=jdbc.queryForList("SELECT a.source_snapshot::text FROM logistics.tms_completion w JOIN logistics.tms_assignment a ON a.id=w.assignment_id JOIN logistics.tms_task_dispatch t ON t.task_id=w.task_id WHERE w.order_id=? AND w.source_order_version=? AND w.task_id=? AND t.company_scope=?",order,version,id,TmsStore.SCOPE);if(rows.size()!=1)throw ClosureFailure.corrupt();descriptors.add(json.readValue(rows.getFirst().get("source_snapshot").toString(),TaskQueries.Source.class));}return tasks(order,version,descriptors);
 }
 private long blockers(UUID task){return jdbc.queryForObject("SELECT count(*) FROM logistics.tms_exception WHERE task_id=? AND status<>'RESOLVED'",Long.class,task);}
 private long occupations(UUID task){return jdbc.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation r JOIN logistics.tms_assignment a ON a.id=r.assignment_id WHERE a.task_id=? AND r.released_at IS NULL AND r.completion_id IS NULL",Long.class,task);}
 private void validate(String function,UUID id){try{jdbc.queryForList("SELECT logistics."+function+"(?)",id);}catch(org.springframework.dao.DataAccessException failure){throw ClosureFailure.corrupt();}}
}
