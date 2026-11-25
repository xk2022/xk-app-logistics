package com.xksystem.logistics.upms.internal;

import com.xksystem.logistics.upms.AccountRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface AccountRepository extends JpaRepository<AccountEntity, UUID> {
    Optional<AccountEntity> findByUsername(String username);
    List<AccountEntity> findAllByOrderByUsernameAsc();
    long countByRoleAndEnabled(AccountRole role, boolean enabled);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findLockedById(@Param("id") UUID id);
}
