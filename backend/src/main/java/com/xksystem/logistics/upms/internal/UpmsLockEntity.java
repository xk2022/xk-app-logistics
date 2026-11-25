package com.xksystem.logistics.upms.internal;

import jakarta.persistence.*;

@Entity @Table(name="upms_lock", schema="logistics")
class UpmsLockEntity {
    @Id int id;
    protected UpmsLockEntity() {}
}
