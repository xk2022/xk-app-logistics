package com.xksystem.logistics.tms.evidence;

import com.xksystem.logistics.tms.PolicyMode;
import java.util.UUID;

/**
 * Private storage with mandatory durability in the caller's SAME database transaction.
 * An external nontransactional provider cannot silently implement this contract.
 */
public interface EvidenceStorage {
    PolicyMode mode();
    void store(UUID objectId,byte[] bytes,String sha256);
    byte[] load(UUID objectId);
}
