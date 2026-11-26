package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.evidence.*;
import com.xksystem.logistics.tms.execution.ExecutionDtos;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class EvidenceStore {
    record Stored(EvidenceDtos.Evidence evidence,UUID objectId,UUID driverId) {}
    record Receipt(String hash,UUID driverId,EvidenceDtos.Evidence evidence) {}
    private final JdbcTemplate jdbc;private final JsonMapper json;
    EvidenceStore(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    Optional<Stored> find(UUID assignment,UUID evidence) {
        var rows=jdbc.queryForList("SELECT * FROM logistics.tms_execution_evidence WHERE assignment_id=? AND id=?",assignment,evidence);
        return rows.isEmpty()?Optional.empty():Optional.of(stored(rows.getFirst()));
    }
    TmsDtos.Page<EvidenceDtos.Evidence> page(UUID assignment,EvidenceValidation.Page query) {
        long count=jdbc.queryForObject("SELECT count(*) FROM logistics.tms_execution_evidence WHERE assignment_id=?",Long.class,assignment);
        var rows=jdbc.queryForList("SELECT * FROM logistics.tms_execution_evidence WHERE assignment_id=? ORDER BY uploaded_at,id LIMIT ? OFFSET ?",assignment,query.size(),(long)query.page()*query.size());
        return TmsDtos.Page.of(rows.stream().map(row->stored(row).evidence()).toList(),query.page(),query.size(),count);
    }
    private Stored stored(Map<String,Object> row) {
        var dto=new EvidenceDtos.Evidence((UUID)row.get("id"),(UUID)row.get("assignment_id"),(UUID)row.get("task_id"),ExecutionPhase.valueOf(row.get("phase").toString()),
                ((Number)row.get("execution_version")).longValue(),row.get("media_type").toString(),((Number)row.get("byte_size")).longValue(),row.get("sha256").toString(),((Timestamp)row.get("uploaded_at")).toInstant(),row.get("status").toString());
        return new Stored(dto,(UUID)row.get("object_id"),(UUID)row.get("driver_id"));
    }
    Optional<Receipt> receipt(UUID actor,String path,String key) {
        var rows=jdbc.queryForList("SELECT request_hash,driver_id,original_data::text FROM logistics.tms_execution_evidence_command WHERE company_scope=? AND actor_id=? AND http_method='POST' AND canonical_path=? AND idempotency_key=?",TmsStore.SCOPE,actor,path,key);
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
        return Optional.of(new Receipt(row.get("request_hash").toString(),(UUID)row.get("driver_id"),json.readValue(row.get("original_data").toString(),EvidenceDtos.Evidence.class)));
    }
    void upload(ExecutionService.Owned owned,UUID objectId,EvidenceDtos.Evidence evidence,ExecutionEvidencePolicy.Requirements rule,String path,String key,String hash) {
        var at=OffsetDateTime.ofInstant(evidence.uploadedAt(),ZoneOffset.UTC);
        jdbc.update("INSERT INTO logistics.tms_execution_evidence(id,assignment_id,task_id,driver_id,actor_id,phase,execution_version,media_type,byte_size,sha256,uploaded_at,status,object_id,policy_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,'VERIFIED',?,?::json)",
                evidence.id(),evidence.assignmentId(),evidence.taskId(),owned.driverId(),owned.actorId(),evidence.phase().name(),evidence.executionVersion(),evidence.mediaType(),evidence.byteSize(),evidence.sha256(),at,objectId,json.writeValueAsString(rule));
        jdbc.update("INSERT INTO logistics.tms_execution_evidence_command(id,company_scope,actor_id,driver_id,assignment_id,task_id,evidence_id,http_method,canonical_path,idempotency_key,request_hash,original_status,original_data,created_at) VALUES (?,?,?,?,?,?,?,'POST',?,?,?,200,?::json,?)",
                UUID.randomUUID(),TmsStore.SCOPE,owned.actorId(),owned.driverId(),evidence.assignmentId(),evidence.taskId(),evidence.id(),path,key,hash,json.writeValueAsString(evidence),at);
    }
    void progress(ExecutionService.Owned owned,ExecutionDtos.View before,EvidenceDtos.ProgressResult result,List<UUID> evidenceIds,String path,String key,String hash,Instant now) {
        var at=OffsetDateTime.ofInstant(now,ZoneOffset.UTC);
        if(jdbc.update("UPDATE logistics.tms_execution SET phase=?,version=version+1,updated_at=greatest(updated_at,?) WHERE assignment_id=? AND version=? AND phase=?",
                result.execution().phase().name(),at,result.assignmentId(),before.version(),before.phase().name())!=1)throw EvidenceFailure.conflict("INVALID_STATE","執行狀態無法更新");
        UUID event=UUID.randomUUID();String snapshot=json.writeValueAsString(result);
        jdbc.update("INSERT INTO logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version,actor_id,occurred_at,snapshot) VALUES (?,?,?,?,?,?,?,?,?::json)",
                event,result.assignmentId(),before.phase().name(),result.execution().phase().name(),before.version(),result.execution().version(),owned.actorId(),at,snapshot);
        for(int position=0;position<evidenceIds.size();position++)jdbc.update("INSERT INTO logistics.tms_execution_event_evidence(id,event_id,evidence_id,assignment_id,input_position,from_phase,from_version) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(),event,evidenceIds.get(position),result.assignmentId(),position,before.phase().name(),before.version());
        jdbc.update("INSERT INTO logistics.tms_execution_command(id,company_scope,actor_id,driver_id,http_method,canonical_path,idempotency_key,request_hash,assignment_id,task_id,original_status,original_data,created_at) VALUES (?,?,?,?,'POST',?,?,?,?,?,200,?::json,?)",
                UUID.randomUUID(),TmsStore.SCOPE,owned.actorId(),owned.driverId(),path,key,hash,result.assignmentId(),result.taskId(),snapshot,at);
    }
}
