package com.xksystem.logistics.tms.exceptions;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.evidence.EvidenceDtos;
import com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy;
import com.xksystem.logistics.tms.execution.ExecutionDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ExceptionDtos {
    private ExceptionDtos(){}
    public record Summary(UUID id,UUID assignmentId,UUID taskId,String taskNo,ReportedPhase reportedPhase,ExceptionStatus status,long version,boolean blocking,Instant reportedAt) {}
    public record ActiveRequest(UUID id,String reason,Instant requestedAt,int uploadedCount,List<UUID> evidenceIds){public ActiveRequest{evidenceIds=List.copyOf(evidenceIds);}}
    public record Record(UUID id,UUID assignmentId,UUID taskId,String taskNo,ReportedPhase reportedPhase,long reportedExecutionVersion,
            String description,ExceptionStatus status,long version,boolean blocking,Instant reportedAt,Instant resolvedAt,String resolutionNote,ActiveRequest activeRequest) {}
    public record Availability(boolean allowed,String blocker) {}
    public record Actions(PolicyMode mode,Availability requestSupplement,Availability resolve,Availability uploadSupplement,
            Availability submitSupplement,ExecutionEvidencePolicy.Requirements requirements) {}
    public record Detail(Record exception,ExecutionDtos.View execution,Actions actions) {}
    public record UploadResult(UUID exceptionId,UUID assignmentId,UUID taskId,long version,UUID requestId,EvidenceDtos.Evidence evidence) {}
    public record SupplementEvidence(UUID requestId,boolean submitted,EvidenceDtos.Evidence evidence) {}
    public record DriverEvent(UUID id,UUID exceptionId,String type,ExceptionStatus fromStatus,ExceptionStatus toStatus,Long fromVersion,long toVersion,
            Instant occurredAt,String note,UUID requestId,List<UUID> evidenceIds) {public DriverEvent{evidenceIds=List.copyOf(evidenceIds);}}
    public record Actor(String role,String displayName) {}
    public record ManagerEvent(UUID id,UUID exceptionId,String type,ExceptionStatus fromStatus,ExceptionStatus toStatus,Long fromVersion,long toVersion,
            Instant occurredAt,String note,UUID requestId,List<UUID> evidenceIds,Actor actor) {public ManagerEvent{evidenceIds=List.copyOf(evidenceIds);}}
}
