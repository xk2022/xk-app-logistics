package com.xksystem.logistics.crm.internal;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
interface ContactRepository extends JpaRepository<ContactEntity,UUID>,JpaSpecificationExecutor<ContactEntity> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from ContactEntity c where c.id=:id and c.customerId=:customerId")
 Optional<ContactEntity> locked(@Param("customerId") UUID customerId,@Param("id") UUID id);
}
