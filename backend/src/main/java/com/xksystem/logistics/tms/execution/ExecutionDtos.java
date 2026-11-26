package com.xksystem.logistics.tms.execution;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tom.TaskQueries;
import java.time.Instant;
import java.util.UUID;

public final class ExecutionDtos {
    private ExecutionDtos() {}
    public record View(ExecutionPhase phase,long version,Instant departedAt) {
        public static View waiting() { return new View(ExecutionPhase.WAITING,0,null); }
    }
    /** The original fourteen-field current-task projection plus independent execution state. */
    public record Detail(UUID assignmentId,UUID taskId,String taskNo,TaskQueries.Item item,String taskKind,
            String pickupName,String pickupAddress,String deliveryName,String deliveryAddress,
            TaskQueries.Contact siteContact,String plannedStartAt,String plannedEndAt,Instant assignedAt,
            PolicyMode policyMode,View execution) {}
    /** An immutable command receipt, not a refreshed task detail. */
    public record DepartureResult(UUID assignmentId,UUID taskId,View execution) {}
}
