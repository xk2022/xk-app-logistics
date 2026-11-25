package com.xksystem.logistics.adm.internal;

import com.xksystem.logistics.adm.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="adm_dict_item",schema="logistics",uniqueConstraints=@UniqueConstraint(columnNames={"category_id","code"}))
class ItemEntity {
 @Id UUID id;
 @Column(name="category_id",nullable=false,updatable=false) UUID categoryId;
 @Column(nullable=false,length=64,updatable=false) String code;
 @Column(nullable=false,length=160) String label;
 @Column(nullable=false) boolean enabled;
 @Column(name="sort_order",nullable=false) int sortOrder;
 @Column(name="read_only",nullable=false,updatable=false) boolean readOnly;
 @Version long version;
 @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
 @Column(name="updated_at",nullable=false) Instant updatedAt;
 protected ItemEntity() {}
 AdmDtos.Item dto(){return new AdmDtos.Item(id,categoryId,code,label,enabled,sortOrder,readOnly,version,createdAt,updatedAt);}
}
