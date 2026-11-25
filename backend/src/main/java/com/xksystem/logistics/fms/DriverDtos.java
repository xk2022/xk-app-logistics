package com.xksystem.logistics.fms;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class DriverDtos {
    private DriverDtos() {}
    public record PageData<T>(List<T> content,int page,int size,long totalElements,int totalPages) {
        public static <T> PageData<T> from(Page<T> page) {
            return new PageData<>(page.getContent(),page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages());
        }
    }
    public record Driver(UUID id,String driverCode,String name,String phone,String email,UUID accountId,
                         String accountUsername,String accountDisplayName,boolean accountBindingValid,
                         EmploymentStatus employmentStatus,DutyStatus dutyStatus,UUID licenseType,
                         String licenseTypeCode,String licenseTypeLabel,boolean licenseTypeEnabled,String licenseReference,
                         LocalDate licenseDueDate,long version,Instant createdAt,UUID createdBy,Instant updatedAt,UUID updatedBy) {}
    public record DriverSummary(UUID id,String driverCode,String name,String phone,String email,UUID accountId,
                                String accountUsername,String accountDisplayName,boolean accountBindingValid,
                                EmploymentStatus employmentStatus,DutyStatus dutyStatus,UUID licenseType,
                                String licenseTypeCode,String licenseTypeLabel,boolean licenseTypeEnabled,LocalDate licenseDueDate,long version) {}
    public record DriverSelf(UUID id,String driverCode,String name,String phone,String email,
                             EmploymentStatus employmentStatus,DutyStatus dutyStatus,UUID licenseType,
                             String licenseTypeCode,String licenseTypeLabel,boolean licenseTypeEnabled,LocalDate licenseDueDate) {}
    public record DriverOption(UUID id,String driverCode,String name,UUID licenseType,String licenseTypeCode,
                               String licenseTypeLabel,boolean licenseTypeEnabled,LocalDate licenseDueDate,long version) {}
    public record DriverLicenseType(UUID id,String code,String label) {}
    public record DriverAccountOption(UUID id,String username,String displayName,boolean bound) {}
}
