package com.xksystem.logistics.adm.internal;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
interface ParameterRepository extends JpaRepository<ParameterEntity,UUID>, JpaSpecificationExecutor<ParameterEntity> {
 Optional<ParameterEntity> findByKey(String key);
 Optional<ParameterEntity> findByKeyAndEnabledTrue(String key);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from ParameterEntity p where p.id=:id") Optional<ParameterEntity> locked(@Param("id") UUID id);
}
