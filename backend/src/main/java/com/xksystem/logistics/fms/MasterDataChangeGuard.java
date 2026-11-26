package com.xksystem.logistics.fms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** FMS-owned extension point; implementations must not release allocations or mutate source identities. */
public interface MasterDataChangeGuard {
    record VehicleState(UUID id,AdministrativeStatus administrativeStatus,UUID vehicleType,BigDecimal capacityTon,LocalDate inspectionDueDate) {}
    record DriverState(UUID id,EmploymentStatus employmentStatus,DutyStatus dutyStatus,UUID licenseType,LocalDate licenseDueDate) {}
    void beforeVehicleChange(VehicleState previous,VehicleState proposed);
    void beforeDriverChange(DriverState previous,DriverState proposed);
}
