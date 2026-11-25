package com.xksystem.logistics.adm.internal;

import com.xksystem.logistics.adm.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="adm_change_log",schema="logistics")
class ChangeLogEntity {
 @Id UUID id;
 @Column(name="target_type",nullable=false,length=16) String targetType;
 @Column(name="target_id",nullable=false) UUID targetId;
 @Column(nullable=false,length=16) String action;
 @Column(name="actor_id",nullable=false) UUID actorId;
 @Column(name="actor_username",nullable=false,length=64) String actorUsername;
 @Column(name="occurred_at",nullable=false) Instant occurredAt;
 @JdbcTypeCode(SqlTypes.JSON) @Column(name="before_value",columnDefinition="jsonb") Map<String,Object> beforeValue;
 @JdbcTypeCode(SqlTypes.JSON) @Column(name="after_value",nullable=false,columnDefinition="jsonb") Map<String,Object> afterValue;
 protected ChangeLogEntity() {}
 AdmDtos.ChangeLog dto(){return new AdmDtos.ChangeLog(id,targetType,targetId,action,actorId,actorUsername,occurredAt,beforeValue,afterValue);}
}
