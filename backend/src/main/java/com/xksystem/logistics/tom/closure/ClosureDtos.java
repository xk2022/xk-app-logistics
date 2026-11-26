package com.xksystem.logistics.tom.closure;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.completion.CompletionDtos;
import com.xksystem.logistics.tms.execution.ExecutionDtos;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import java.time.Instant;
import java.util.UUID;
public final class ClosureDtos {
 private ClosureDtos(){}
 public record Actor(String role,String displayName){}
 public record ConfirmationSummary(UUID completionId,UUID assignmentId,UUID taskId,String taskNo,UUID orderId,String orderNo,long sourceOrderVersion,Instant completedAt,long version,String state,Instant confirmedAt){}
 public record ConfirmationFact(UUID id,long version,Instant confirmedAt,PolicyMode policyMode,String reason,Actor actor){}
 public record ConfirmationPolicyView(PolicyMode mode,boolean canConfirm,String blocker){}
 public record ConfirmationDetail(ConfirmationSummary record,ExecutionDtos.View execution,CompletionDtos.Witness completion,ConfirmationFact confirmation,ConfirmationPolicyView policy){}
 public record ConfirmationResult(UUID completionId,UUID assignmentId,UUID taskId,long sourceOrderVersion,long version,ConfirmationFact confirmation){}
 public record Summary(UUID orderId,String orderNo,String type,String lifecycleStatus,String acceptanceStatus,long version,String auditState,Instant updatedAt,Instant closedAt){}
 public record Counts(long taskCount,long completedTaskCount,long confirmedTaskCount,long blockingExceptionCount,long effectiveOccupationCount,long sourceOrderVersion){}
 public record Requirements(String taskBasis,boolean requiresManagerConfirmation,boolean requiresNoBlockingExceptions,boolean requiresNoEffectiveOccupations){}
 public record PolicyView(PolicyMode mode,boolean canClose,String blocker,Requirements requirements){}
 public record Witness(UUID id,long sourceVersion,long version,Instant closedAt,PolicyMode policyMode,boolean managerConfirmed,String reason,long taskCount,Actor actor){}
 public record View(UUID orderId,String orderNo,String lifecycleStatus,String acceptanceStatus,long version,String auditState,Witness closure,PolicyView policy,Counts counts){}
 public record TaskReview(UUID orderId,long sourceOrderVersion,UUID taskId,String taskNo,Boolean requiredTask,UUID assignmentId,UUID completionId,ExecutionPhase executionPhase,long executionVersion,boolean completionVerified,long confirmationVersion,Instant confirmedAt,long blockingExceptionCount,long effectiveOccupationCount){}
 public record Result(UUID orderId,String orderNo,String lifecycleStatus,String acceptanceStatus,long version,Witness closure){}
}
