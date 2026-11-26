package com.xksystem.logistics.tms;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.fms.VehicleQueries;
import com.xksystem.logistics.tom.TaskQueries;

/** Production is fail-closed. Any synthetic implementation belongs exclusively to isolated test artifacts. */
public interface DispatchEligibilityPolicy {
    PolicyMode mode();
    boolean driverApproved(DriverQueries.Snapshot driver);
    boolean vehicleApproved(VehicleQueries.Snapshot vehicle,ResourcePosition position);
    boolean combinationApproved(TaskQueries.Source source,DriverQueries.Snapshot driver,
                                VehicleQueries.Snapshot main,VehicleQueries.Snapshot trailer);
}
