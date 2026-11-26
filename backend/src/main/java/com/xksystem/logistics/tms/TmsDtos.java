package com.xksystem.logistics.tms;

import com.xksystem.logistics.tom.TaskQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TmsDtos {
    private TmsDtos() {}
    public record Page<T>(List<T> content,int page,int size,long totalElements,long totalPages) {
        public Page {content=List.copyOf(content);}
        public static <T> Page<T> of(List<T> content,int page,int size,long count){return new Page<>(content,page,size,count,(count+size-1)/size);}
    }
    public record TaskSummary(UUID taskId,String taskNo,UUID orderId,String orderNo,String orderType,long orderVersion,
                              long generation,String taskKind,String pickupName,String deliveryName,boolean prerequisiteReady,
                              UUID currentAssignmentId,PolicyMode policyMode,List<String> blockingReasons) {}
    public record DriverSnapshot(UUID id,String driverCode,String name,long version) {}
    public record VehicleSnapshot(UUID id,String plateNo,UUID vehicleType,String vehicleTypeCode,String vehicleTypeLabel,long version) {}
    public record Reservation(UUID id,ResourceKind kind,ResourcePosition position,UUID resourceId,Instant acquiredAt,Instant releasedAt,String releaseReason) {}
    public record Event(UUID id,String action,UUID assignmentId,UUID previousAssignmentId,long fromGeneration,long toGeneration,
                        UUID actorId,Instant occurredAt,String reason) {}
    public record Assignment(UUID id,UUID taskId,UUID orderId,String status,long version,long assignedGeneration,Long endedGeneration,
                             DriverSnapshot driver,VehicleSnapshot mainVehicle,VehicleSnapshot trailer,TaskQueries.Source source,
                             PolicyMode policyMode,UUID assignedBy,Instant assignedAt,String assignmentReason,UUID endedBy,
                             Instant endedAt,String endReason,UUID previousAssignmentId,List<Reservation> reservations,List<Event> events) {}
    public record DispatchDetail(TaskSummary task,Assignment current,Page<Assignment> history) {}
    public record CommandResult(UUID taskId,long generation,Assignment assignment) {}
    public record Occupation(UUID assignmentId,UUID taskId) {}
    public record ResourceCandidate(UUID id,ResourceKind kind,ResourcePosition position,String code,String label,long version,
                                    boolean administrativelyAvailable,boolean occupied,Occupation occupation,PolicyMode policyMode,
                                    boolean policyApproved,List<String> blockingReasons) {}
    public record MyTask(UUID assignmentId,UUID taskId,String taskNo,TaskQueries.Item item,String taskKind,String pickupName,
                         String pickupAddress,String deliveryName,String deliveryAddress,TaskQueries.Contact siteContact,
                         String plannedStartAt,String plannedEndAt,Instant assignedAt,PolicyMode policyMode) {}
}
