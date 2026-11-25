package com.xksystem.logistics.upms.internal;

import com.xksystem.logistics.upms.AccountRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface AccountRepository extends JpaRepository<AccountEntity, UUID>,JpaSpecificationExecutor<AccountEntity> {
    interface DriverCandidateView { UUID getId(); String getUsername(); String getDisplayName(); }
    interface DriverAccountView {
        UUID getId(); String getUsername(); String getDisplayName(); AccountRole getRole();
        boolean getEnabled(); boolean getMustChangePassword(); java.time.Instant getTemporaryPasswordExpiresAt();
    }
    @Query("select a.id as id,a.username as username,a.displayName as displayName,a.role as role,a.enabled as enabled,a.mustChangePassword as mustChangePassword,a.temporaryPasswordExpiresAt as temporaryPasswordExpiresAt from AccountEntity a where a.id=:id")
    Optional<DriverAccountView> driverSummary(@Param("id") UUID id);
    Optional<AccountEntity> findByUsername(String username);
    List<AccountEntity> findAllByOrderByUsernameAsc();
    long countByRoleAndEnabled(AccountRole role, boolean enabled);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> findLockedById(@Param("id") UUID id);
}
