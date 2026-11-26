package com.xksystem.logistics.tom.closure;
import com.xksystem.logistics.tms.PolicyMode;
import java.util.List;
public interface OrderClosurePolicy {
 record TaskContext(String taskKind,String driverCode,Boolean requiredTask){}
 PolicyMode mode();
 boolean allows(List<TaskContext> tasks);
}
