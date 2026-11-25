package com.xksystem.logistics.fms.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface VehicleRepository extends JpaRepository<VehicleEntity,UUID>,JpaSpecificationExecutor<VehicleEntity> {
    boolean existsByPlateNo(String plateNo);
    boolean existsByPlateNoAndIdNot(String plateNo,UUID id);
}
