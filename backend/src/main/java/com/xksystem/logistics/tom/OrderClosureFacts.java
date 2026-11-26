package com.xksystem.logistics.tom;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/** TMS-owned facts consumed by TOM without any service callback/FMS lock. */
public interface OrderClosureFacts {
 record Totals(long blockingExceptionCount,long effectiveOccupationCount){}
 record TaskFact(UUID taskId,UUID assignmentId,UUID completionId,ExecutionPhase executionPhase,long executionVersion,
   boolean verified,boolean sourceMatches,UUID confirmationId,long confirmationVersion,Instant confirmedAt,String driverCode,long blockingExceptionCount,long effectiveOccupationCount){}
 /** TOM root and caller canonical first; locks ALL owned order rows, including zero-case roots. */
 Totals lockOrder(UUID orderId);
 Totals totals(UUID orderId);
 /** <=100 server-owned current source descriptors; does not impose a whole-graph quota. */
 Map<UUID,TaskFact> tasks(UUID orderId,long sourceVersion,List<TaskQueries.Source> tasks);
 /** Immutable recorded source, for a TOM-owned manifest after closure; max100 IDs. */
 Map<UUID,TaskFact> recorded(UUID orderId,long sourceVersion,List<UUID> taskIds);
}
