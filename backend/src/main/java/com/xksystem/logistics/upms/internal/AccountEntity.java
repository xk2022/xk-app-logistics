package com.xksystem.logistics.upms.internal;

import com.xksystem.logistics.upms.AccountDto;
import com.xksystem.logistics.upms.AccountRole;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upms_account", schema = "logistics")
class AccountEntity {
    @Id UUID id;
    @Column(nullable=false, unique=true, length=64) String username;
    @Column(name="display_name", nullable=false, length=160) String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=16) AccountRole role;
    @Column(nullable=false) boolean enabled;
    @Column(name="must_change_password", nullable=false) boolean mustChangePassword;
    @Column(name="password_hash", nullable=false, length=512) String passwordHash;
    @Column(name="temporary_password_expires_at") Instant temporaryPasswordExpiresAt;
    @Column(name="auth_version", nullable=false) long authVersion;
    @Version long version;
    @Column(name="created_at", nullable=false) Instant createdAt;
    @Column(name="updated_at", nullable=false) Instant updatedAt;
    protected AccountEntity() {}
    AccountDto dto() { return new AccountDto(id, username, displayName, role, enabled, mustChangePassword, version); }
}
