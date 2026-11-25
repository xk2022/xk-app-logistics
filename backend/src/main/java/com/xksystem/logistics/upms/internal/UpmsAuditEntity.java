package com.xksystem.logistics.upms.internal;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="upms_audit", schema="logistics")
class UpmsAuditEntity {
    @Id UUID id;
    @Column(nullable=false, length=128) String actor;
    @Column(name="target_id") UUID targetId;
    @Column(nullable=false, length=64) String action;
    @Column(nullable=false, length=500) String reason;
    @Column(name="occurred_at", nullable=false) Instant occurredAt;
    protected UpmsAuditEntity() {}
    UpmsAuditEntity(String actor, UUID targetId, String action, String reason, Instant now) {
        this.id=UUID.randomUUID(); this.actor=actor; this.targetId=targetId;
        this.action=action; this.reason=reason; this.occurredAt=now;
    }
}
