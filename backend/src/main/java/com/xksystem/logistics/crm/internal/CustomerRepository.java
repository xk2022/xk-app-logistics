package com.xksystem.logistics.crm.internal;
import java.util.*;
import org.springframework.data.jpa.repository.*;
interface CustomerRepository extends JpaRepository<CustomerEntity,UUID>,JpaSpecificationExecutor<CustomerEntity> {
 Optional<CustomerEntity> findByCustomerCode(String code);
}
