package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.tms.exceptions.*;
import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.upms.AccountDto;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class ExceptionStore {
    record State(ExceptionDtos.Record value,UUID driverId,TaskQueries.Source source,TmsDtos.DriverSnapshot driver,PolicyMode policyMode) {}
    record Request(UUID id,UUID exceptionId,UUID assignmentId,String reason,Instant requestedAt) {}
    record Receipt(String hash,UUID exceptionId,UUID assignmentId,UUID driverId,String type,String data) {}
    private final JdbcTemplate jdbc;private final JsonMapper json;
    ExceptionStore(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    State state(UUID id,boolean lock) {
        var rows=jdbc.queryForList("SELECT c.* FROM logistics.tms_exception c JOIN logistics.tms_task_dispatch d ON d.task_id=c.task_id WHERE c.id=? AND d.company_scope=?"+(lock?" FOR UPDATE OF c":""),id,TmsStore.SCOPE);
        if(rows.isEmpty())throw ExceptionFailure.missing();return state(rows.getFirst());
    }
    private State state(Map<String,Object> row) {
        UUID assignment=uuid(row,"assignment_id"),id=uuid(row,"id"),request=uuid(row,"active_request_id");
        ExceptionDtos.ActiveRequest active=null;
        if(request!=null){var current=request(id,assignment,request);var ids=requestEvidence(id,request);if(ids.size()>32)throw new IllegalStateException("Exception request exceeds its technical bound");active=new ExceptionDtos.ActiveRequest(request,current.reason(),current.requestedAt(),ids.size(),ids);}
        var value=new ExceptionDtos.Record(id,assignment,uuid(row,"task_id"),row.get("task_no").toString(),ReportedPhase.valueOf(row.get("reported_phase").toString()),number(row,"reported_execution_version"),
                text(row,"description"),ExceptionStatus.valueOf(row.get("status").toString()),number(row,"version"),!row.get("status").equals("RESOLVED"),instant(row,"reported_at"),instant(row,"resolved_at"),text(row,"resolution_note"),active);
        return new State(value,uuid(row,"driver_id"),json.readValue(row.get("source_snapshot").toString(),TaskQueries.Source.class),json.readValue(row.get("driver_snapshot").toString(),TmsDtos.DriverSnapshot.class),PolicyMode.valueOf(row.get("policy_mode").toString()));
    }
    Request request(UUID exception,UUID assignment,UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM logistics.tms_exception_supplement_request WHERE id=? AND exception_id=? AND assignment_id=?",id,exception,assignment);
        if(rows.isEmpty())throw ExceptionFailure.missing();var row=rows.getFirst();return new Request(id,exception,assignment,text(row,"reason"),instant(row,"requested_at"));
    }
    List<UUID> requestEvidence(UUID exception,UUID request) {
        return jdbc.query("SELECT evidence_id FROM logistics.tms_exception_supplement_evidence WHERE exception_id=? AND request_id=? ORDER BY evidence_id",(row,index)->row.getObject(1,UUID.class),exception,request);
    }
    List<UUID> submittedEvidence(UUID exception) {
        return jdbc.query("SELECT evidence_id FROM logistics.tms_exception_event_evidence WHERE event_id=(SELECT id FROM logistics.tms_exception_event WHERE exception_id=? AND type='SUPPLEMENT_SUBMITTED' ORDER BY to_version DESC,id LIMIT 1) ORDER BY evidence_id",(row,index)->row.getObject(1,UUID.class),exception);
    }
    boolean linked(UUID exception,UUID request,UUID evidence) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM logistics.tms_exception_supplement_evidence WHERE exception_id=? AND request_id=? AND evidence_id=?)",Boolean.class,exception,request,evidence));
    }
    TmsDtos.Page<ExceptionDtos.Summary> page(ExceptionValidation.Query query,UUID assignment) {
        String where=" FROM logistics.tms_exception c JOIN logistics.tms_task_dispatch d ON d.task_id=c.task_id WHERE d.company_scope=?";
        var args=new ArrayList<Object>();args.add(TmsStore.SCOPE);UUID filter=assignment==null?query.assignmentId():assignment;
        if(filter!=null){where+=" AND c.assignment_id=?";args.add(filter);}if(query.status()!=null){where+=" AND c.status=?";args.add(query.status().name());}
        long count=jdbc.queryForObject("SELECT count(*)"+where,Long.class,args.toArray());var pageArgs=new ArrayList<>(args);pageArgs.add(query.size());pageArgs.add((long)query.page()*query.size());
        var values=jdbc.queryForList("SELECT c.*"+where+" ORDER BY c.reported_at DESC,c.id LIMIT ? OFFSET ?",pageArgs.toArray()).stream().map(row->{var value=state(row).value();return new ExceptionDtos.Summary(value.id(),value.assignmentId(),value.taskId(),value.taskNo(),value.reportedPhase(),value.status(),value.version(),value.blocking(),value.reportedAt());}).toList();
        return TmsDtos.Page.of(values,query.page(),query.size(),count);
    }
    List<Map<String,Object>> events(UUID exception,ExceptionValidation.Query query) {
        return jdbc.queryForList("SELECT * FROM logistics.tms_exception_event WHERE exception_id=? ORDER BY to_version DESC,id LIMIT ? OFFSET ?",exception,query.size(),(long)query.page()*query.size());
    }
    private List<UUID> eventEvidence(Map<String,Object> event) {
        String table=event.get("type").equals("SUPPLEMENT_EVIDENCE_UPLOADED")?"tms_exception_supplement_evidence":"tms_exception_event_evidence";
        return jdbc.query("SELECT evidence_id FROM logistics."+table+" WHERE event_id=? ORDER BY evidence_id",(row,index)->row.getObject(1,UUID.class),uuid(event,"id"));
    }
    ExceptionDtos.DriverEvent driverEvent(Map<String,Object> row) {
        return new ExceptionDtos.DriverEvent(uuid(row,"id"),uuid(row,"exception_id"),row.get("type").toString(),status(row.get("from_status")),status(row.get("to_status")),row.get("from_version")==null?null:number(row,"from_version"),number(row,"to_version"),instant(row,"occurred_at"),text(row,"note"),uuid(row,"request_id"),eventEvidence(row));
    }
    ExceptionDtos.ManagerEvent managerEvent(Map<String,Object> row) {
        var event=driverEvent(row);return new ExceptionDtos.ManagerEvent(event.id(),event.exceptionId(),event.type(),event.fromStatus(),event.toStatus(),event.fromVersion(),event.toVersion(),event.occurredAt(),event.note(),event.requestId(),event.evidenceIds(),new ExceptionDtos.Actor(row.get("actor_role").toString(),text(row,"actor_display_name")));
    }
    long eventCount(UUID exception){return jdbc.queryForObject("SELECT count(*) FROM logistics.tms_exception_event WHERE exception_id=?",Long.class,exception);}
    TmsDtos.Page<ExceptionDtos.SupplementEvidence> evidencePage(UUID exception,ExceptionValidation.Query query,EvidenceStore common) {
        long count=jdbc.queryForObject("SELECT count(*) FROM logistics.tms_exception_supplement_evidence WHERE exception_id=?",Long.class,exception);
        var rows=jdbc.queryForList("SELECT r.assignment_id,r.request_id,r.evidence_id,EXISTS(SELECT 1 FROM logistics.tms_exception_event_evidence x WHERE x.evidence_id=r.evidence_id AND x.exception_id=r.exception_id) AS submitted FROM logistics.tms_exception_supplement_evidence r JOIN logistics.tms_execution_evidence e ON e.id=r.evidence_id WHERE r.exception_id=? ORDER BY e.uploaded_at,e.id LIMIT ? OFFSET ?",exception,query.size(),(long)query.page()*query.size());
        var values=rows.stream().map(row->new ExceptionDtos.SupplementEvidence(uuid(row,"request_id"),Boolean.TRUE.equals(row.get("submitted")),common.find(uuid(row,"assignment_id"),uuid(row,"evidence_id")).orElseThrow(ExceptionFailure::missing).evidence())).toList();
        return TmsDtos.Page.of(values,query.page(),query.size(),count);
    }
    boolean belongs(UUID exception,UUID evidence){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM logistics.tms_exception_supplement_evidence WHERE exception_id=? AND evidence_id=?)",Boolean.class,exception,evidence));}
    Optional<Receipt> receipt(UUID actor,String path,String key) {
        var rows=jdbc.queryForList("SELECT * FROM logistics.tms_exception_command WHERE company_scope=? AND actor_id=? AND http_method='POST' AND canonical_path=? AND idempotency_key=?",TmsStore.SCOPE,actor,path,key);
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();return Optional.of(new Receipt(row.get("request_hash").toString(),uuid(row,"exception_id"),uuid(row,"assignment_id"),uuid(row,"driver_id"),row.get("result_type").toString(),row.get("original_data").toString()));
    }
    <T>T result(Receipt receipt,Class<T> type){return json.readValue(receipt.data(),type);}
    void report(UUID id,ExecutionService.Owned owned,String description,Instant now,PolicyMode mode) {
        var assignment=owned.assignment();var source=assignment.source();
        jdbc.update("INSERT INTO logistics.tms_exception(id,assignment_id,task_id,driver_id,task_no,reported_phase,reported_execution_version,description,source_snapshot,driver_snapshot,policy_mode,actor_id,reported_at,status,version) VALUES (?,?,?,?,?,?,?,?,?::json,?::json,?,?,?,'OPEN',0)",
                id,assignment.id(),assignment.taskId(),owned.driverId(),source.taskNo(),executionPhase(assignment.id()),executionVersion(assignment.id()),bytes(description),json.writeValueAsString(source),json.writeValueAsString(assignment.driver()),mode.name(),owned.actorId(),at(now));
    }
    private String executionPhase(UUID assignment){return jdbc.queryForObject("SELECT phase FROM logistics.tms_execution WHERE assignment_id=?",String.class,assignment);}
    private long executionVersion(UUID assignment){return jdbc.queryForObject("SELECT version FROM logistics.tms_execution WHERE assignment_id=?",Long.class,assignment);}
    void request(State before,UUID request,String reason,AccountDto actor,Instant now,Object rule) {
        var value=before.value();jdbc.update("INSERT INTO logistics.tms_exception_supplement_request(id,exception_id,assignment_id,requested_version,reason,actor_id,requested_at,policy_snapshot) VALUES (?,?,?,?,?,?,?,?::json)",request,value.id(),value.assignmentId(),value.version()+1,bytes(reason),actor.id(),at(now),json.writeValueAsString(rule));
        update(value,ExceptionStatus.NEEDS_SUPPLEMENT,request,null,null);
    }
    void update(ExceptionDtos.Record before,ExceptionStatus status,UUID request,String resolution,Instant resolvedAt) {
        if(jdbc.update("UPDATE logistics.tms_exception SET status=?,version=version+1,active_request_id=?,resolved_at=?,resolution_note=? WHERE id=? AND version=? AND version<?",status.name(),request,resolvedAt==null?null:at(resolvedAt),bytes(resolution),before.id(),before.version(),TmsValidation.MAX)!=1)throw ExceptionFailure.conflict("INVALID_STATE","異常狀態無法更新");
    }
    UUID event(State after,String type,ExceptionDtos.Record before,String note,UUID request,AccountDto actor,Instant now,Object snapshot) {
        UUID id=UUID.randomUUID();var value=after.value();
        jdbc.update("INSERT INTO logistics.tms_exception_event(id,exception_id,assignment_id,type,from_status,to_status,from_version,to_version,request_id,note,actor_id,actor_role,actor_display_name,occurred_at,snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::json)",id,value.id(),value.assignmentId(),type,before==null?null:before.status().name(),value.status().name(),before==null?null:before.version(),value.version(),request,bytes(note),actor.id(),actor.role().name(),bytes(actor.displayName()),at(now),json.writeValueAsString(snapshot));return id;
    }
    void attach(State state,UUID request,UUID evidence,UUID event) {
        jdbc.update("INSERT INTO logistics.tms_exception_supplement_evidence(id,exception_id,assignment_id,request_id,evidence_id,event_id) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),state.value().id(),state.value().assignmentId(),request,evidence,event);
    }
    void selected(State state,UUID request,UUID event,List<UUID> ids) {
        for(int i=0;i<ids.size();i++)jdbc.update("INSERT INTO logistics.tms_exception_event_evidence(id,event_id,exception_id,assignment_id,request_id,evidence_id,input_position) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),event,state.value().id(),state.value().assignmentId(),request,ids.get(i),i);
    }
    void remember(State state,UUID actor,UUID request,String path,String key,String hash,Object result,boolean upload,Instant now) {
        var value=state.value();jdbc.update("INSERT INTO logistics.tms_exception_command(id,company_scope,actor_id,exception_id,assignment_id,task_id,driver_id,request_id,http_method,canonical_path,idempotency_key,request_hash,original_status,result_type,original_data,created_at) VALUES (?,?,?,?,?,?,?,?,'POST',?,?,?,200,?,?::json,?)",UUID.randomUUID(),TmsStore.SCOPE,actor,value.id(),value.assignmentId(),value.taskId(),state.driverId(),request,path,key,hash,upload?"UPLOAD":"RECORD",json.writeValueAsString(result),at(now));
    }
    void lockAssignment(UUID assignment) {
        var ids=jdbc.query("SELECT a.task_id FROM logistics.tms_assignment a JOIN logistics.tms_task_dispatch d ON d.task_id=a.task_id WHERE a.id=? AND d.company_scope=?",(row,index)->row.getObject(1,UUID.class),assignment,TmsStore.SCOPE);
        if(ids.isEmpty())throw ExceptionFailure.missing();jdbc.queryForList("SELECT task_id FROM logistics.tms_task_dispatch WHERE task_id=? AND company_scope=? FOR UPDATE",ids.getFirst(),TmsStore.SCOPE);
        if(jdbc.queryForList("SELECT id FROM logistics.tms_assignment WHERE id=? FOR UPDATE",assignment).isEmpty())throw ExceptionFailure.missing();
    }
    long unresolved(UUID assignment) {
        var statuses=jdbc.queryForList("SELECT status FROM logistics.tms_exception WHERE assignment_id=? ORDER BY id FOR UPDATE",String.class,assignment);
        return statuses.stream().map(ExceptionStatus::valueOf).filter(status->status!=ExceptionStatus.RESOLVED).count();
    }
    private static ExceptionStatus status(Object value){return value==null?null:ExceptionStatus.valueOf(value.toString());}
    private static UUID uuid(Map<String,Object> row,String key){return (UUID)row.get(key);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Instant instant(Map<String,Object> row,String key){return row.get(key)==null?null:((Timestamp)row.get(key)).toInstant();}
    private static String text(Map<String,Object> row,String key){if(row.get(key)==null)return null;try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap((byte[])row.get(key))).toString();}catch(java.nio.charset.CharacterCodingException corrupt){throw new IllegalStateException("Stored exception text is invalid",corrupt);}}
    private static byte[] bytes(String text){return text==null?null:text.getBytes(StandardCharsets.UTF_8);}
    private static OffsetDateTime at(Instant time){return OffsetDateTime.ofInstant(time,ZoneOffset.UTC);}
}
