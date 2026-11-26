package com.xksystem.logistics.tms.completion;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.execution.ExecutionDtos;
import com.xksystem.logistics.tom.TaskQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CompletionDtos {
    private CompletionDtos() {}
    public record Witness(UUID id,Instant completedAt,Instant releasedAt,PolicyMode policyMode,boolean safeReleaseConfirmed) {}
    public record Detail(UUID assignmentId,UUID taskId,String taskNo,TaskQueries.Item item,String taskKind,
            String pickupName,String pickupAddress,String deliveryName,String deliveryAddress,
            TaskQueries.Contact siteContact,String plannedStartAt,String plannedEndAt,Instant assignedAt,
            PolicyMode policyMode,ExecutionDtos.View execution,Witness completion) {}
    public record Result(UUID assignmentId,UUID taskId,ExecutionDtos.View execution,Witness completion) {}
    public record PolicyView(UUID assignmentId,UUID taskId,ExecutionDtos.View execution,PolicyMode mode,
            boolean canComplete,String blocker,CompletionPolicy.Requirements requirements,List<UUID> requiredEvidenceIds) {}
}
