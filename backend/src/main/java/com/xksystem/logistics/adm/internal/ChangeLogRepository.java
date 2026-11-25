package com.xksystem.logistics.adm.internal;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
interface ChangeLogRepository extends JpaRepository<ChangeLogEntity,UUID>, JpaSpecificationExecutor<ChangeLogEntity> {

}
