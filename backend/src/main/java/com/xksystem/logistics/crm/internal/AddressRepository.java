package com.xksystem.logistics.crm.internal;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
interface AddressRepository extends JpaRepository<AddressEntity,UUID>,JpaSpecificationExecutor<AddressEntity> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from AddressEntity a where a.id=:id and a.customerId=:customerId")
 Optional<AddressEntity> locked(@Param("customerId") UUID customerId,@Param("id") UUID id);
}
