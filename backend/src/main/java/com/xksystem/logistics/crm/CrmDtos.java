package com.xksystem.logistics.crm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public final class CrmDtos {
    private CrmDtos() {}
    public record PageData<T>(List<T> content,int page,int size,long totalElements,int totalPages) {
        public static <T> PageData<T> from(Page<T> value) {
            return new PageData<>(value.getContent(),value.getNumber(),value.getSize(),value.getTotalElements(),value.getTotalPages());
        }
    }
    public record Customer(UUID id,String customerCode,String name,String taxId,String phone,String email,
                           boolean enabled,long version,Instant createdAt,Instant updatedAt,UUID createdBy,UUID updatedBy) {}
    public record Contact(UUID id,UUID customerId,String name,String title,String phone,String email,
                          boolean enabled,long version,Instant createdAt,Instant updatedAt,UUID createdBy,UUID updatedBy) {}
    public record Address(UUID id,UUID customerId,String label,String address,String addressType,
                          boolean enabled,long version,Instant createdAt,Instant updatedAt,UUID createdBy,UUID updatedBy) {}
    public record CustomerOption(UUID id,String customerCode,String name,long version) {}
}
