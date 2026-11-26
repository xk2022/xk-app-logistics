package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tms.*;
import com.xksystem.logistics.fms.*;
import com.xksystem.logistics.tom.TaskQueries;
import org.springframework.stereotype.Component;

/** No setting, parameter, profile or HTTP operation can turn production qualification on. */
@Component
public final class UnconfirmedDispatchEligibilityPolicy implements DispatchEligibilityPolicy {
    public PolicyMode mode(){return PolicyMode.UNCONFIRMED;}
    public boolean driverApproved(DriverQueries.Snapshot driver){return false;}
    public boolean vehicleApproved(VehicleQueries.Snapshot vehicle,ResourcePosition position){return false;}
    public boolean combinationApproved(TaskQueries.Source source,DriverQueries.Snapshot driver,VehicleQueries.Snapshot main,VehicleQueries.Snapshot trailer){return false;}
}
