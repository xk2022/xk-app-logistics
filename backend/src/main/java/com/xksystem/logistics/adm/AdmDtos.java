package com.xksystem.logistics.adm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class AdmDtos {
    private AdmDtos() {}
    public record PageData<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageData<T> from(Page<T> value) {
            return new PageData<>(value.getContent(), value.getNumber(), value.getSize(), value.getTotalElements(), value.getTotalPages());
        }
    }
    public record Category(UUID id, String code, String name, boolean enabled, int sortOrder, boolean readOnly,
                           long version, Instant createdAt, Instant updatedAt) {}
    public record Item(UUID id, UUID categoryId, String code, String label, boolean enabled, int sortOrder,
                       boolean readOnly, long version, Instant createdAt, Instant updatedAt) {}
    public record Parameter(UUID id, String key, String name, ParameterType valueType, String valueText,
                            String description, boolean enabled, long version, Instant createdAt, Instant updatedAt) {}
    public record Definition(String key, String name, ParameterType valueType, String description,
                             Integer minLength, Integer maxLength, List<String> allowedValues,
                             String minValue, String maxValue, Integer decimalScale) {}
    public record ChangeLog(UUID id, String targetType, UUID targetId, String action, UUID actorId,
                            String actorUsername, Instant occurredAt, Map<String,Object> before, Map<String,Object> after) {}
}
