package com.xksystem.logistics.adm.internal;

import com.xksystem.logistics.adm.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="adm_dict_category",schema="logistics")
class CategoryEntity {
 @Id UUID id;
 @Column(nullable=false,unique=true,length=64,updatable=false) String code;
 @Column(nullable=false,length=160) String name;
 @Column(nullable=false) boolean enabled;
 @Column(name="sort_order",nullable=false) int sortOrder;
 @Column(name="read_only",nullable=false,updatable=false) boolean readOnly;
 @Version long version;
 @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
 @Column(name="updated_at",nullable=false) Instant updatedAt;
 protected CategoryEntity() {}
 AdmDtos.Category dto(){return new AdmDtos.Category(id,code,name,enabled,sortOrder,readOnly,version,createdAt,updatedAt);}
}
