package com.xksystem.logistics.crm.internal;
import com.xksystem.logistics.crm.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="crm_contact",schema="logistics")
class ContactEntity {
 @Id UUID id;
 @Column(name="customer_id",nullable=false,updatable=false) UUID customerId;
 @Column(nullable=false,length=200) String name;
 @Column(length=100) String title;
 @Column(length=50) String phone;
 @Column(length=254) String email;
 @Column(nullable=false) boolean enabled;
 @Version long version;
 @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
 @Column(name="updated_at",nullable=false) Instant updatedAt;
 @Column(name="created_by",nullable=false,updatable=false) UUID createdBy;
 @Column(name="updated_by",nullable=false) UUID updatedBy;
 protected ContactEntity() {}
 CrmDtos.Contact dto(){return new CrmDtos.Contact(id,customerId,name,title,phone,email,enabled,version,createdAt,updatedAt,createdBy,updatedBy);}
}
