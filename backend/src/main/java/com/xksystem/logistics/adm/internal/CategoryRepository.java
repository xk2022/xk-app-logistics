package com.xksystem.logistics.adm.internal;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
interface CategoryRepository extends JpaRepository<CategoryEntity,UUID>, JpaSpecificationExecutor<CategoryEntity> {
 Optional<CategoryEntity> findByCode(String code);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from CategoryEntity c where c.id=:id") Optional<CategoryEntity> locked(@Param("id") UUID id);
}
