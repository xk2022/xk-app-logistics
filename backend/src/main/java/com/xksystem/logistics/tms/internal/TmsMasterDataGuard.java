package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.fms.*;
import com.xksystem.logistics.tms.ResourceKind;
import com.xksystem.logistics.upms.UpmsException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** FMS source -> own reservation locks. No source callback or reversed module dependency. */
@Component @Transactional(propagation=Propagation.MANDATORY)
class TmsMasterDataGuard implements MasterDataChangeGuard {
    private final TmsStore store;TmsMasterDataGuard(TmsStore store){this.store=store;}
    public void beforeVehicleChange(VehicleState before,VehicleState after){
        boolean changed=before.administrativeStatus()!=after.administrativeStatus()&&after.administrativeStatus()!=AdministrativeStatus.AVAILABLE||!Objects.equals(before.vehicleType(),after.vehicleType())||!numericEqual(before.capacityTon(),after.capacityTon())||!Objects.equals(before.inspectionDueDate(),after.inspectionDueDate());
        if(changed&&store.occupation(ResourceKind.VEHICLE,before.id(),true).isPresent())throw new UpmsException(409,"車輛仍有有效派工，請先撤改派",Map.of("administrativeStatus","有效占用期間不可變更行政資格或配置依據"));
    }
    public void beforeDriverChange(DriverState before,DriverState after){
        boolean adminChanged=before.employmentStatus()!=after.employmentStatus()||before.dutyStatus()!=after.dutyStatus();
        boolean changed=adminChanged&&(after.employmentStatus()!=EmploymentStatus.ACTIVE||after.dutyStatus()!=DutyStatus.ON_DUTY)||!Objects.equals(before.licenseType(),after.licenseType())||!Objects.equals(before.licenseDueDate(),after.licenseDueDate());
        if(changed&&store.occupation(ResourceKind.DRIVER,before.id(),true).isPresent())throw new UpmsException(409,"司機仍有有效派工，請先撤改派",Map.of("employmentStatus","有效占用期間不可變更行政資格或配置依據"));
    }
    private static boolean numericEqual(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
}
