package com.xksystem.logistics.crm.internal;
import com.xksystem.logistics.crm.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="crm_address",schema="logistics")
class AddressEntity {
 @Id UUID id;
 @Column(name="customer_id",nullable=false,updatable=false) UUID customerId;
 @Column(length=100) String label;
 @Column(nullable=false,length=1000) String address;
 @Column(name="address_type",length=40) String addressType;
 @Column(nullable=false) boolean enabled;
 @Version long version;
 @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
 @Column(name="updated_at",nullable=false) Instant updatedAt;
 @Column(name="created_by",nullable=false,updatable=false) UUID createdBy;
 @Column(name="updated_by",nullable=false) UUID updatedBy;
 protected AddressEntity() {}
 CrmDtos.Address dto(){return new CrmDtos.Address(id,customerId,label,address,addressType,enabled,version,createdAt,updatedAt,createdBy,updatedBy);}
}
