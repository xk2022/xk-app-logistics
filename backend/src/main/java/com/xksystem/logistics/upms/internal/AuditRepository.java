package com.xksystem.logistics.upms.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditRepository extends JpaRepository<UpmsAuditEntity, UUID> {}
