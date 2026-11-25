package com.xksystem.logistics.upms;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;

/** Trusted FMS account-binding boundary. This grants no UPMS HTTP or account-management rights. */
public interface DriverAccountQueries {
    record Summary(UUID id,String username,String displayName,boolean bindingValid) {}
    record Candidate(UUID id,String username,String displayName) {}
    /** Current minimal summary, including invalid retained bindings; no credentials or auth metadata. */
    Optional<Summary> findCurrent(UUID accountId);
    /** Eligible current DRIVER accounts, including ones already bound by the caller's domain. */
    Page<Candidate> candidates(int page,int size,String q);
    /** Requires the writable caller transaction and its canonical/actor locks first. */
    Summary requireEligibleForBinding(UUID accountId);
}
