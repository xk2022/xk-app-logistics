package com.xksystem.logistics.fms;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class VehicleDtos {
    private VehicleDtos() {}
    public record PageData<T>(List<T> content,int page,int size,long totalElements,int totalPages) {
        public static <T> PageData<T> from(Page<T> value) {
            return new PageData<>(value.getContent(),value.getNumber(),value.getSize(),value.getTotalElements(),value.getTotalPages());
        }
    }
    public record Vehicle(UUID id,String plateNo,UUID vehicleType,String vehicleTypeCode,String vehicleTypeLabel,
                          boolean vehicleTypeEnabled,String capacityTon,String registrationNo,LocalDate registrationDate,
                          LocalDate inspectionDueDate,AdministrativeStatus administrativeStatus,long version,
                          Instant createdAt,UUID createdBy,Instant updatedAt,UUID updatedBy) {}
    public record VehicleOption(UUID id,String plateNo,UUID vehicleType,String vehicleTypeCode,String vehicleTypeLabel,
                                boolean vehicleTypeEnabled,String capacityTon,long version) {}
    public record VehicleType(UUID id,String code,String label) {}
}
