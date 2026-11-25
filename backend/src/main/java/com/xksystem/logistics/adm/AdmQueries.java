package com.xksystem.logistics.adm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Internal business-module query interface; HTTP exposure still requires explicit role authorization. */
public interface AdmQueries {
    List<AdmDtos.Item> enabledOptions(String categoryCode);
    Optional<String> historicalLabel(String categoryCode, String itemCode);
    Optional<String> historicalLabel(UUID itemId);
    Optional<AdmDtos.Parameter> enabledParameter(String key);
}
