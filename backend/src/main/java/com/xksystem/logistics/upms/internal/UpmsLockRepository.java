package com.xksystem.logistics.upms.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

interface UpmsLockRepository extends JpaRepository<UpmsLockEntity, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from UpmsLockEntity l where l.id = 1")
    UpmsLockEntity lock();
}
