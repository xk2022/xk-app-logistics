package com.xksystem.logistics.crm;

import java.util.Optional;
import java.util.UUID;

/** Trusted server lookup, not an HTTP authorization grant and not an order snapshot store. */
public interface CrmQueries {
    record Snapshot(UUID id,String code,String name,long version) {}
    /** Current master data, including disabled records. Never use it to overwrite saved order snapshots. */
    Optional<Snapshot> findCurrent(UUID customerId);
    /**
     * Requires the caller's writable transaction and customer-manager authorization first.
     * Locks the customer until caller commit, coordinating new references with disable/rename.
     */
    Snapshot requireEnabledForNewReference(UUID customerId);
}
