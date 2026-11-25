package com.xksystem.logistics.fms;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Trusted master-data source only; grants no HTTP, account-management or dispatch authorization. */
public interface DriverQueries {
    record Snapshot(UUID id,String driverCode,String name,String phone,String email,EmploymentStatus employmentStatus,
                    DutyStatus dutyStatus,UUID licenseType,String licenseTypeCode,String licenseTypeLabel,
                    boolean licenseTypeEnabled,LocalDate licenseDueDate,long version) {}
    /** Current master source for every employment/duty state, excluding account and license-reference details. */
    Optional<Snapshot> findCurrent(UUID driverId);
    /**
     * Requires the writable caller transaction and caller authorization/canonical locks first.
     * Locks and refreshes the driver until commit and checks only ACTIVE && ON_DUTY.
     * This neither validates legal/dispatch eligibility nor reserves a driver.
     */
    Snapshot requireAdministrativelyAvailable(UUID driverId);
}
