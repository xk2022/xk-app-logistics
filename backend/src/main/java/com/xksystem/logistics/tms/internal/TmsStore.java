package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.tom.TaskQueries;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Own TMS persistence only. JdbcTemplate joins the caller's existing JPA transaction. */
@Repository
class TmsStore {
    static final String SCOPE="SINGLE_COMPANY";
    record Anchor(UUID taskId,UUID orderId,String orderNo,String taskNo,long sourceOrderVersion,long generation) {}
    record Replay(String hash,UUID taskId,TmsDtos.CommandResult result) {}
    record JsonValue(Object value) {}
    private final JdbcTemplate jdbc;private final JsonMapper json;
    TmsStore(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    void index(List<TaskQueries.Seed> seeds){for(var seed:seeds)jdbc.update("INSERT INTO logistics.tms_task_dispatch(task_id,order_id,company_scope,order_no,task_no,source_order_version,generation) VALUES (?,?,?,?,?,?,0) ON CONFLICT(task_id) DO NOTHING",seed.taskId(),seed.orderId(),SCOPE,seed.orderNo(),seed.taskNo(),seed.sourceOrderVersion());}
    Anchor anchor(UUID task,boolean lock){var rows=jdbc.queryForList("SELECT * FROM logistics.tms_task_dispatch WHERE task_id=? AND company_scope=?"+(lock?" FOR UPDATE":""),task,SCOPE);if(rows.isEmpty())throw TmsFailure.missing();return anchor(rows.getFirst());}
    private Anchor anchor(Map<String,Object> row){return new Anchor(id(row,"task_id"),id(row,"order_id"),str(row,"order_no"),str(row,"task_no"),number(row,"source_order_version"),number(row,"generation"));}
    TmsDtos.Page<Anchor> anchors(TmsQuery query){
        String where=" WHERE t.company_scope=?";var args=new ArrayList<Object>();args.add(SCOPE);
        if(query.orderId()!=null){where+=" AND t.order_id=?";args.add(query.orderId());}
        if(!query.state().equals("ALL"))where+=(query.state().equals("ASSIGNED")?" AND EXISTS":" AND NOT EXISTS")+" (SELECT 1 FROM logistics.tms_assignment a WHERE a.task_id=t.task_id AND a.status='ASSIGNED')";
        if(!query.q().isEmpty()){where+=" AND (lower(t.task_no) LIKE lower(?) ESCAPE '\\' OR lower(t.order_no) LIKE lower(?) ESCAPE '\\')";String pattern="%"+literal(query.q())+"%";args.add(pattern);args.add(pattern);}
        long count=jdbc.queryForObject("SELECT count(*) FROM logistics.tms_task_dispatch t"+where,Long.class,args.toArray());
        var pageArgs=new ArrayList<>(args);pageArgs.add(query.size());pageArgs.add((long)query.page()*query.size());
        var values=jdbc.queryForList("SELECT t.* FROM logistics.tms_task_dispatch t"+where+" ORDER BY t.task_no,t.task_id LIMIT ? OFFSET ?",pageArgs.toArray()).stream().map(this::anchor).toList();
        return TmsDtos.Page.of(values,query.page(),query.size(),count);
    }
    Optional<TmsDtos.Assignment> current(UUID task){var rows=jdbc.queryForList("SELECT a.id FROM logistics.tms_assignment a JOIN logistics.tms_task_dispatch t ON t.task_id=a.task_id WHERE a.task_id=? AND a.status='ASSIGNED' AND t.company_scope=?",task,SCOPE);return rows.isEmpty()?Optional.empty():Optional.of(assignment(id(rows.getFirst(),"id"),null));}
    TmsDtos.Assignment assignment(UUID assignment,boolean lock){return assignment(assignment,null,lock);}
    TmsDtos.Assignment assignment(UUID assignment,TmsDtos.Event extra){return assignment(assignment,extra,false);}
    private TmsDtos.Assignment assignment(UUID assignment,TmsDtos.Event extra,boolean lock){
        var rows=jdbc.queryForList("SELECT a.* FROM logistics.tms_assignment a JOIN logistics.tms_task_dispatch t ON t.task_id=a.task_id WHERE a.id=? AND t.company_scope=?"+(lock?" FOR UPDATE OF a":""),assignment,SCOPE);
        if(rows.isEmpty())throw TmsFailure.missing();var row=rows.getFirst();var events=new ArrayList<>(events(assignment));if(extra!=null)events.add(extra);
        return new TmsDtos.Assignment(assignment,id(row,"task_id"),id(row,"order_id"),str(row,"status"),number(row,"version"),number(row,"assigned_generation"),nullableNumber(row,"ended_generation"),
                decoded(row,"driver_snapshot",TmsDtos.DriverSnapshot.class),decoded(row,"main_vehicle_snapshot",TmsDtos.VehicleSnapshot.class),decoded(row,"trailer_snapshot",TmsDtos.VehicleSnapshot.class),decoded(row,"source_snapshot",TaskQueries.Source.class),
                PolicyMode.valueOf(str(row,"policy_mode")),id(row,"assigned_by"),instant(row,"assigned_at"),str(row,"assignment_reason"),id(row,"ended_by"),instant(row,"ended_at"),str(row,"end_reason"),id(row,"previous_assignment_id"),reservations(assignment),List.copyOf(events));
    }
    TmsDtos.Page<TmsDtos.Assignment> history(UUID task,int page,int size){long count=jdbc.queryForObject("SELECT count(*) FROM logistics.tms_assignment WHERE task_id=?",Long.class,task);
        var rows=jdbc.queryForList("SELECT id FROM logistics.tms_assignment WHERE task_id=? ORDER BY assigned_generation DESC,id LIMIT ? OFFSET ?",task,size,(long)page*size);
        return TmsDtos.Page.of(rows.stream().map(row->assignment(id(row,"id"),null)).toList(),page,size,count);}
    TmsDtos.Page<TmsDtos.Assignment> self(UUID driver,int page,int size){String where=" FROM logistics.tms_assignment a JOIN logistics.tms_task_dispatch t ON t.task_id=a.task_id WHERE a.driver_id=? AND a.status='ASSIGNED' AND t.company_scope=?";
        long count=jdbc.queryForObject("SELECT count(*)"+where,Long.class,driver,SCOPE);
        var rows=jdbc.queryForList("SELECT a.id"+where+" ORDER BY t.task_no,a.id LIMIT ? OFFSET ?",driver,SCOPE,size,(long)page*size);
        return TmsDtos.Page.of(rows.stream().map(row->assignment(id(row,"id"),null)).toList(),page,size,count);}
    List<TmsDtos.Reservation> reservations(UUID assignment){return jdbc.queryForList("SELECT * FROM logistics.tms_resource_reservation WHERE assignment_id=? ORDER BY CASE position WHEN 'DRIVER' THEN 0 WHEN 'MAIN' THEN 1 ELSE 2 END,id",assignment).stream().map(row->new TmsDtos.Reservation(id(row,"id"),ResourceKind.valueOf(str(row,"resource_kind")),ResourcePosition.valueOf(str(row,"position")),id(row,"resource_id"),instant(row,"acquired_at"),instant(row,"released_at"),str(row,"release_reason"))).toList();}
    List<TmsDtos.Event> events(UUID assignment){return jdbc.queryForList("SELECT * FROM logistics.tms_dispatch_event WHERE assignment_id=? OR previous_assignment_id=? ORDER BY to_generation,id",assignment,assignment).stream().map(row->new TmsDtos.Event(id(row,"id"),str(row,"action"),id(row,"assignment_id"),id(row,"previous_assignment_id"),number(row,"from_generation"),number(row,"to_generation"),id(row,"actor_id"),instant(row,"occurred_at"),str(row,"reason"))).toList();}
    Optional<TmsDtos.Occupation> occupation(ResourceKind kind,UUID resource,boolean lock){var rows=jdbc.queryForList("SELECT r.assignment_id,a.task_id FROM logistics.tms_resource_reservation r JOIN logistics.tms_assignment a ON a.id=r.assignment_id WHERE r.resource_kind=? AND r.resource_id=? AND r.released_at IS NULL AND r.completion_id IS NULL"+(lock?" FOR UPDATE OF r":""),kind.name(),resource);return rows.isEmpty()?Optional.empty():Optional.of(new TmsDtos.Occupation(id(rows.getFirst(),"assignment_id"),id(rows.getFirst(),"task_id")));}
    boolean effective(TmsDtos.Assignment assignment){
        if(assignment.reservations().size()!=(assignment.trailer()==null?2:3))return false;
        var expected=new EnumMap<ResourcePosition,UUID>(ResourcePosition.class);expected.put(ResourcePosition.DRIVER,assignment.driver().id());expected.put(ResourcePosition.MAIN,assignment.mainVehicle().id());if(assignment.trailer()!=null)expected.put(ResourcePosition.TRAILER,assignment.trailer().id());
        for(var row:assignment.reservations()){UUID resource=expected.remove(row.position());var kind=row.position()==ResourcePosition.DRIVER?ResourceKind.DRIVER:ResourceKind.VEHICLE;
            if(resource==null||!resource.equals(row.resourceId())||kind!=row.kind()||row.releasedAt()!=null||row.releaseReason()!=null||occupation(kind,resource,false).filter(value->value.assignmentId().equals(assignment.id())&&value.taskId().equals(assignment.taskId())).isEmpty())return false;}
        return expected.isEmpty();
    }
    void advance(Anchor anchor){if(jdbc.update("UPDATE logistics.tms_task_dispatch SET generation=generation+1 WHERE task_id=? AND company_scope=? AND generation=? AND generation<?",anchor.taskId(),SCOPE,anchor.generation(),TmsValidation.MAX)!=1)throw TmsFailure.conflict("INVALID_STATE","任務派工版本不能更新");}
    void createAssignment(UUID assignment,Anchor anchor,long generation,TmsDtos.DriverSnapshot driver,TmsDtos.VehicleSnapshot main,TmsDtos.VehicleSnapshot trailer,TaskQueries.Source source,PolicyMode mode,UUID actor,Instant now,String reason,UUID previous){
        var row=new LinkedHashMap<String,Object>();row.put("id",assignment);row.put("task_id",anchor.taskId());row.put("order_id",anchor.orderId());row.put("status","ASSIGNED");row.put("version",0L);row.put("assigned_generation",generation);
        row.put("driver_id",driver.id());row.put("main_vehicle_id",main.id());row.put("trailer_id",trailer==null?null:trailer.id());row.put("driver_snapshot",new JsonValue(driver));row.put("main_vehicle_snapshot",new JsonValue(main));row.put("trailer_snapshot",trailer==null?null:new JsonValue(trailer));row.put("source_snapshot",new JsonValue(source));
        row.put("policy_mode",mode.name());row.put("assigned_by",actor);row.put("assigned_at",timestamp(now));row.put("assignment_reason",reason);row.put("previous_assignment_id",previous);insert("tms_assignment",row);
        reservation(assignment,ResourcePosition.DRIVER,driver.id(),now);reservation(assignment,ResourcePosition.MAIN,main.id(),now);if(trailer!=null)reservation(assignment,ResourcePosition.TRAILER,trailer.id(),now);
    }
    private void reservation(UUID assignment,ResourcePosition position,UUID resource,Instant now){var row=new LinkedHashMap<String,Object>();row.put("id",UUID.randomUUID());row.put("assignment_id",assignment);row.put(position==ResourcePosition.DRIVER?"driver_id":"vehicle_id",resource);row.put("position",position.name());row.put("acquired_at",timestamp(now));insert("tms_resource_reservation",row);}
    void end(TmsDtos.Assignment assignment,String status,long generation,UUID actor,Instant now,String reason){
        int released=jdbc.update("UPDATE logistics.tms_resource_reservation SET released_at=?,release_reason=? WHERE assignment_id=? AND released_at IS NULL AND completion_id IS NULL",timestamp(now),reason,assignment.id());
        if(released!=(assignment.trailer()==null?2:3))throw TmsFailure.conflict("INVALID_STATE","原派工的有效占用不完整");
        if(jdbc.update("UPDATE logistics.tms_assignment SET status=?,version=1,ended_generation=?,ended_by=?,ended_at=?,end_reason=? WHERE id=? AND status='ASSIGNED' AND version=0",status,generation,actor,timestamp(now),reason,assignment.id())!=1)throw TmsFailure.conflict("INVALID_STATE","目前派工不能撤改");
    }
    void event(UUID task,TmsDtos.Event event,TmsDtos.CommandResult snapshot){var row=new LinkedHashMap<String,Object>();row.put("id",event.id());row.put("task_id",task);row.put("action",event.action());row.put("assignment_id",event.assignmentId());row.put("previous_assignment_id",event.previousAssignmentId());row.put("from_generation",event.fromGeneration());row.put("to_generation",event.toGeneration());row.put("actor_id",event.actorId());row.put("occurred_at",timestamp(event.occurredAt()));row.put("reason",event.reason());row.put("snapshot",new JsonValue(snapshot));insert("tms_dispatch_event",row);}
    boolean tryKey(UUID actor,String path,String key){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((SCOPE+"\n"+actor+"\nPOST\n"+path+"\n"+key).getBytes(StandardCharsets.UTF_8));return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,ByteBuffer.wrap(digest).getLong()));}catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
    Optional<Replay> replay(UUID actor,String path,String key){var rows=jdbc.queryForList("SELECT request_hash,task_id,original_data::text FROM logistics.tms_idempotency WHERE company_scope=? AND actor_id=? AND http_method='POST' AND canonical_path=? AND idempotency_key=?",SCOPE,actor,path,key);return rows.isEmpty()?Optional.empty():Optional.of(new Replay(str(rows.getFirst(),"request_hash"),id(rows.getFirst(),"task_id"),decoded(rows.getFirst(),"original_data",TmsDtos.CommandResult.class)));}
    void remember(UUID actor,String path,String key,String hash,TmsDtos.CommandResult result,Instant now){var row=new LinkedHashMap<String,Object>();row.put("id",UUID.randomUUID());row.put("company_scope",SCOPE);row.put("actor_id",actor);row.put("http_method","POST");row.put("canonical_path",path);row.put("idempotency_key",key);row.put("request_hash",hash);row.put("task_id",result.taskId());row.put("original_status",200);row.put("original_data",new JsonValue(result));row.put("created_at",timestamp(now));insert("tms_idempotency",row);}
    private void insert(String table,LinkedHashMap<String,Object> row){var slots=new ArrayList<String>();var values=new ArrayList<Object>();row.values().forEach(value->{slots.add(value instanceof JsonValue?"CAST(? AS json)":"?");values.add(value instanceof JsonValue j?json.writeValueAsString(j.value()):value);});if(jdbc.update("INSERT INTO logistics."+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",slots)+")",values.toArray())!=1)throw new IllegalStateException("TMS insert failed");}
    private <T>T decoded(Map<String,Object> row,String key,Class<T> type){String value=str(row,key);return value==null?null:json.readValue(value,type);}
    private static String literal(String value){return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    private static UUID id(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value instanceof UUID uuid?uuid:UUID.fromString(value.toString());}
    private static String str(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value.toString();}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Long nullableNumber(Map<String,Object> row,String key){return row.get(key)==null?null:number(row,key);}
    private static Instant instant(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:((Timestamp)value).toInstant();}
    private static Timestamp timestamp(Instant value){return Timestamp.from(value);}
}
