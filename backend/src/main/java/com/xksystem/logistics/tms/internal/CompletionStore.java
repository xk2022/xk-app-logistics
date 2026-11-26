package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.TmsValidation;
import com.xksystem.logistics.tms.completion.*;
import com.xksystem.logistics.tms.execution.ExecutionDtos;
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
class CompletionStore {
    record Receipt(String hash,UUID driverId,CompletionDtos.Result result) {}
    record Proof(UUID eventId,UUID commandId,List<UUID> evidenceIds) {}
    private final JdbcTemplate jdbc;private final JsonMapper json;
    CompletionStore(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    Optional<CompletionDtos.Witness> witness(UUID assignment){var rows=jdbc.queryForList("SELECT * FROM logistics.tms_completion WHERE assignment_id=?",assignment);return rows.isEmpty()?Optional.empty():Optional.of(witness(rows.getFirst()));}
    private CompletionDtos.Witness witness(Map<String,Object> row){return new CompletionDtos.Witness((UUID)row.get("id"),((Timestamp)row.get("completed_at")).toInstant(),((Timestamp)row.get("released_at")).toInstant(),PolicyMode.valueOf(row.get("policy_mode").toString()),(Boolean)row.get("safe_release_confirmed"));}
    void validate(CompletionDtos.Witness witness){
        try{jdbc.queryForList("SELECT logistics.validate_tms_completion(?)",witness.id());}
        // A corrupt historical read is a safe server failure, not a new write's protected-relation rejection.
        catch(org.springframework.dao.DataAccessException failure){throw new CompletionFailure(500,"E0500","完成紀錄暫時無法驗證",null);}
    }
    Optional<Receipt> receipt(UUID actor,String path,String key){var rows=jdbc.queryForList("SELECT driver_id,request_hash,original_data::text FROM logistics.tms_completion_command WHERE company_scope=? AND actor_id=? AND http_method='POST' AND canonical_path=? AND idempotency_key=?",TmsStore.SCOPE,actor,path,key);
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();return Optional.of(new Receipt(row.get("request_hash").toString(),(UUID)row.get("driver_id"),json.readValue(row.get("original_data").toString(),CompletionDtos.Result.class)));}
    Optional<Proof> proof(UUID assignment,long version){
        var events=jdbc.queryForList("SELECT id,logistics.tms_progress_success(id) AS command_id FROM logistics.tms_execution_event WHERE assignment_id=? AND from_phase='IN_PROGRESS' AND to_phase='AWAITING_COMPLETION' AND to_version=?",assignment,version);
        if(events.isEmpty())return Optional.empty();var row=events.getFirst();UUID event=(UUID)row.get("id"),command=(UUID)row.get("command_id");
        if(command==null)throw invalid();
        var ids=jdbc.query("SELECT evidence_id FROM logistics.tms_execution_event_evidence WHERE event_id=? ORDER BY input_position",(rs,index)->rs.getObject(1,UUID.class),event);
        if(ids.isEmpty())return Optional.empty();
        if(ids.size()>32||!ids.equals(ids.stream().distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()))throw invalid();
        return Optional.of(new Proof(event,command,List.copyOf(ids)));
    }
    void complete(ExecutionService.Owned owned,ExecutionDtos.View before,CompletionDtos.Result result,Proof proof,CompletionPolicy.Requirements rule,String path,String key,String hash){
        var assignment=owned.assignment();UUID event=UUID.randomUUID(),receipt=UUID.randomUUID();var at=OffsetDateTime.ofInstant(result.completion().completedAt(),ZoneOffset.UTC);String snapshot=json.writeValueAsString(result);
        if(jdbc.update("UPDATE logistics.tms_execution SET phase='COMPLETED',version=version+1,updated_at=greatest(updated_at,?) WHERE assignment_id=? AND phase='AWAITING_COMPLETION' AND version=? AND version<?",at,assignment.id(),before.version(),TmsValidation.MAX)!=1)throw CompletionFailure.conflict("INVALID_STATE","目前階段不能完成");
        jdbc.update("INSERT INTO logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version,actor_id,occurred_at,snapshot) VALUES (?,?,'AWAITING_COMPLETION','COMPLETED',?,?,?,?,?::json)",event,assignment.id(),before.version(),result.execution().version(),owned.actorId(),at,snapshot);
        var columns="id,assignment_id,task_id,order_id,driver_id,main_vehicle_id,trailer_id,source_order_version,from_execution_version,to_execution_version,terminal_phase,source_event_id,source_command_id,source_from_phase,source_to_phase,source_from_version,completion_event_id,completion_from_phase,completion_to_phase,evidence_ids,actor_id,completed_at,released_at,safe_release_confirmed,policy_mode,policy_snapshot,receipt_id";
        String array="{"+String.join(",",proof.evidenceIds().stream().map(UUID::toString).toList())+"}";
        jdbc.update("INSERT INTO logistics.tms_completion("+columns+") VALUES (?,?,?,?,?,?,?,?,?,?,'COMPLETED',?,?,'IN_PROGRESS','AWAITING_COMPLETION',?,?,'AWAITING_COMPLETION','COMPLETED',?::uuid[],?,?,?,true,'SYNTHETIC_TEST_ONLY',?::json,?)",
                result.completion().id(),assignment.id(),assignment.taskId(),assignment.orderId(),owned.driverId(),assignment.mainVehicle().id(),assignment.trailer()==null?null:assignment.trailer().id(),assignment.source().orderVersion(),before.version(),result.execution().version(),proof.eventId(),proof.commandId(),before.version()-1,event,array,owned.actorId(),at,at,json.writeValueAsString(rule),receipt);
        int marked=0;for(var reservation:assignment.reservations())marked+=jdbc.update("UPDATE logistics.tms_resource_reservation SET completion_id=? WHERE id=? AND assignment_id=? AND position=? AND resource_kind=? AND resource_id=? AND released_at IS NULL AND completion_id IS NULL",result.completion().id(),reservation.id(),assignment.id(),reservation.position().name(),reservation.kind().name(),reservation.resourceId());
        if(marked!=(assignment.trailer()==null?2:3))throw CompletionFailure.conflict("PROTECTED_RELATION","原派工的占用資料無法完整釋放");
        jdbc.update("INSERT INTO logistics.tms_completion_command(id,completion_id,company_scope,actor_id,driver_id,assignment_id,task_id,http_method,canonical_path,idempotency_key,request_hash,original_status,original_data,created_at) VALUES (?,?,?,?,?,?,?,'POST',?,?,?,200,?::json,?)",receipt,result.completion().id(),TmsStore.SCOPE,owned.actorId(),owned.driverId(),assignment.id(),assignment.taskId(),path,key,hash,snapshot,at);
    }
    private static CompletionFailure invalid(){return CompletionFailure.conflict("EVIDENCE_INVALID","必要證據無法驗證");}
}
