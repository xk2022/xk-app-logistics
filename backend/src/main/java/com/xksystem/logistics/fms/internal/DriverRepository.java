package com.xksystem.logistics.fms.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DriverRepository extends JpaRepository<DriverEntity,UUID>,JpaSpecificationExecutor<DriverEntity> {
    boolean existsByDriverCode(String driverCode);
    boolean existsByAccountId(UUID accountId);
    boolean existsByAccountIdAndIdNot(UUID accountId,UUID id);
    Optional<DriverEntity> findByAccountId(UUID accountId);
    @Query("select d.accountId from DriverEntity d where d.accountId in :ids")
    List<UUID> boundAccountIds(@Param("ids") Collection<UUID> ids);
}
