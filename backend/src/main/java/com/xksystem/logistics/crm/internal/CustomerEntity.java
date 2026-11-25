package com.xksystem.logistics.crm.internal;
import com.xksystem.logistics.crm.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="crm_customer",schema="logistics")
class CustomerEntity {
 @Id UUID id;
 @Column(name="customer_code",nullable=false,unique=true,length=64,updatable=false) String customerCode;
 @Column(nullable=false,length=200) String name;
 @Column(name="tax_id",length=50) String taxId;
 @Column(length=50) String phone;
 @Column(length=254) String email;
 @Column(nullable=false) boolean enabled;
 @Version long version;
 @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
 @Column(name="updated_at",nullable=false) Instant updatedAt;
 @Column(name="created_by",nullable=false,updatable=false) UUID createdBy;
 @Column(name="updated_by",nullable=false) UUID updatedBy;
 protected CustomerEntity() {}
 CrmDtos.Customer dto(){return new CrmDtos.Customer(id,customerCode,name,taxId,phone,email,enabled,version,createdAt,updatedAt,createdBy,updatedBy);}
 CrmDtos.CustomerOption option(){return new CrmDtos.CustomerOption(id,customerCode,name,version);}
 CrmQueries.Snapshot snapshot(){return new CrmQueries.Snapshot(id,customerCode,name,version);}
}
