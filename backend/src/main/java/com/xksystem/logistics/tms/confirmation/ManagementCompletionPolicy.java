package com.xksystem.logistics.tms.confirmation;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tom.TaskQueries;
/** Administrative acknowledgement only; no new release or formal safety responsibility. */
public interface ManagementCompletionPolicy {
 PolicyMode mode();
 boolean allows(TaskQueries.Source source,TmsDtos.DriverSnapshot recordedDriver);
}
