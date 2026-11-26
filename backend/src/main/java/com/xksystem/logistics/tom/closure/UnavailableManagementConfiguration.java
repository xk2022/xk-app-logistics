package com.xksystem.logistics.tom.closure;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.confirmation.ManagementCompletionPolicy;
import com.xksystem.logistics.tom.TaskQueries;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class UnavailableManagementConfiguration {
 @Bean public ManagementCompletionPolicy managementCompletionPolicy(){return new ManagementCompletionPolicy(){public PolicyMode mode(){return PolicyMode.UNCONFIRMED;}public boolean allows(TaskQueries.Source source,TmsDtos.DriverSnapshot driver){return false;}};}
 @Bean public OrderClosurePolicy orderClosurePolicy(){return new OrderClosurePolicy(){public PolicyMode mode(){return PolicyMode.UNCONFIRMED;}public boolean allows(List<TaskContext> tasks){return false;}};}
}
