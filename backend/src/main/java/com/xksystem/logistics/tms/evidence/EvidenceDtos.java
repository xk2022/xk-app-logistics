package com.xksystem.logistics.tms.evidence;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.execution.ExecutionDtos;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import java.time.Instant;
import java.util.UUID;

public final class EvidenceDtos {
    private EvidenceDtos() {}
    public record Evidence(UUID id,UUID assignmentId,UUID taskId,ExecutionPhase phase,long executionVersion,
            String mediaType,long byteSize,String sha256,Instant uploadedAt,String status) {}
    public record ProgressResult(UUID assignmentId,UUID taskId,ExecutionDtos.View execution) {}
    public record PolicyView(UUID assignmentId,UUID taskId,ExecutionDtos.View execution,PolicyMode mode,
            ExecutionPhase nextPhase,ExecutionEvidencePolicy.Requirements requirements,String blocker) {}
}
