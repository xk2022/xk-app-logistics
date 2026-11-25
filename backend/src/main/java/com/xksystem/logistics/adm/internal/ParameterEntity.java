package com.xksystem.logistics.adm.internal;

import com.xksystem.logistics.adm.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="adm_parameter",schema="logistics")
class ParameterEntity {
 @Id UUID id;
 @Column(name="parameter_key",nullable=false,unique=true,length=64,updatable=false) String key;
 @Column(nullable=false,length=160) String name;
 @Enumerated(EnumType.STRING) @Column(name="value_type",nullable=false,length=16,updatable=false) ParameterType valueType;
 @Column(name="value_text",nullable=false,length=2000) String valueText;
 @Column(nullable=false,length=1000) String description;
 @Column(nullable=false) boolean enabled;
 @Version long version;
 @Column(name="created_at",nullable=false,updatable=false) Instant createdAt;
 @Column(name="updated_at",nullable=false) Instant updatedAt;
 protected ParameterEntity() {}
 AdmDtos.Parameter dto(){return new AdmDtos.Parameter(id,key,name,valueType,valueText,description,enabled,version,createdAt,updatedAt);}
}
