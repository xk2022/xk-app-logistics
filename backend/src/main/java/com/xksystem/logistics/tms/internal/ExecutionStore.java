package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tms.execution.ExecutionDtos;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Own TMS execution persistence. All calls join the caller's lock-capable transaction. */
@Repository
class ExecutionStore {
    record Receipt(String hash,UUID driverId,ExecutionDtos.DepartureResult result) {}
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    ExecutionStore(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    ExecutionDtos.View view(UUID assignmentId) {
        var rows=jdbc.queryForList("SELECT phase,version,departed_at FROM logistics.tms_execution WHERE assignment_id=?",assignmentId);
        if(rows.isEmpty())return ExecutionDtos.View.waiting();
        var row=rows.getFirst();
        return new ExecutionDtos.View(ExecutionPhase.valueOf(row.get("phase").toString()),
                ((Number)row.get("version")).longValue(),((Timestamp)row.get("departed_at")).toInstant());
    }
    boolean started(UUID assignmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM logistics.tms_execution WHERE assignment_id=?)",Boolean.class,assignmentId));
    }
    Optional<Receipt> receipt(UUID actorId,String path,String key) {
        var rows=jdbc.queryForList("SELECT request_hash,driver_id,original_data::text FROM logistics.tms_execution_command "
                +"WHERE company_scope=? AND actor_id=? AND http_method='POST' AND canonical_path=? AND idempotency_key=?",
                TmsStore.SCOPE,actorId,path,key);
        if(rows.isEmpty())return Optional.empty();
        var row=rows.getFirst();
        return Optional.of(new Receipt(row.get("request_hash").toString(),(UUID)row.get("driver_id"),
                json.readValue(row.get("original_data").toString(),ExecutionDtos.DepartureResult.class)));
    }
    void depart(UUID actorId,UUID driverId,String path,String key,String hash,ExecutionDtos.DepartureResult result,Instant now) {
        OffsetDateTime at=OffsetDateTime.ofInstant(now,ZoneOffset.UTC);
        String snapshot=json.writeValueAsString(result);
        jdbc.update("INSERT INTO logistics.tms_execution(assignment_id,phase,version,departed_at,updated_at) VALUES (?,'DEPARTED',1,?,?)",
                result.assignmentId(),at,at);
        jdbc.update("INSERT INTO logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version,actor_id,occurred_at,snapshot) "
                +"VALUES (?,?,'WAITING','DEPARTED',0,1,?,?,?::json)",UUID.randomUUID(),result.assignmentId(),actorId,at,snapshot);
        jdbc.update("INSERT INTO logistics.tms_execution_command(id,company_scope,actor_id,driver_id,http_method,canonical_path,idempotency_key,request_hash,assignment_id,task_id,original_status,original_data,created_at) "
                +"VALUES (?,?,?,?,'POST',?,?,?,?,?,200,?::json,?)",UUID.randomUUID(),TmsStore.SCOPE,actorId,driverId,path,key,hash,
                result.assignmentId(),result.taskId(),snapshot,at);
    }
}
