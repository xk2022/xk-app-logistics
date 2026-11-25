package com.xksystem.logistics.adm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Internal business-module query interface; HTTP exposure still requires explicit role authorization. */
public interface AdmQueries {
    /** Current category-bound source, including archived values; enabled includes both parent and item. */
    record ItemSnapshot(UUID id, UUID categoryId, String categoryCode, String code, String label,
                        boolean enabled, long version) {}
    List<AdmDtos.Item> enabledOptions(String categoryCode);
    Optional<ItemSnapshot> findItem(String categoryCode, UUID itemId);
    /** Caller must hold its authorization locks first; the writable transaction owns category/item locks until commit. */
    ItemSnapshot requireEnabledItemForNewReference(String categoryCode, UUID itemId);
    Optional<String> historicalLabel(String categoryCode, String itemCode);
    Optional<String> historicalLabel(UUID itemId);
    Optional<AdmDtos.Parameter> enabledParameter(String key);
}
