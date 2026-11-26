package com.xksystem.logistics.fms;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;

/** Trusted server lookup, not an HTTP authorization grant or a resource reservation. */
public interface VehicleQueries {
    record Snapshot(UUID id,String plateNo,UUID vehicleType,String vehicleTypeCode,String vehicleTypeLabel,
                    boolean vehicleTypeEnabled,String capacityTon,AdministrativeStatus administrativeStatus,long version) {}
    /** Current master data, including maintenance/inactive vehicles and archived dictionary values. */
    Optional<Snapshot> findCurrent(UUID vehicleId);
    /**
     * Requires a writable caller transaction and caller authorization/canonical locks first.
     * Locks and refreshes the vehicle source until commit; checks only administrative AVAILABLE.
     * Does not check inspection dates or type activation, promise dispatch eligibility, or reserve a resource.
     */
    Snapshot requireAdministrativelyAvailable(UUID vehicleId);
    /** All administrative states; bounded dispatch candidate source, not eligibility. */
    Page<Snapshot> pageForDispatch(int page,int size,String q);
    /** Caller writable transaction/canonical locks first; current source even when unavailable. */
    Snapshot lockCurrentForDispatch(UUID vehicleId);
}
