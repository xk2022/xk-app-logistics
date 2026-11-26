package com.xksystem.logistics.support;
import com.xksystem.logistics.tms.confirmation.ManagementCompletionPolicy;
import com.xksystem.logistics.tom.closure.OrderClosurePolicy;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
@TestConfiguration(proxyBeanMethods=false)
public class SyntheticManagementConfiguration {
 @Bean @Primary public ManagementCompletionPolicy syntheticManagementCompletionPolicy(DataSource data)throws java.sql.SQLException{return SyntheticManagementPolicies.confirmation(data);}
 @Bean @Primary public OrderClosurePolicy syntheticOrderClosurePolicy(DataSource data)throws java.sql.SQLException{return SyntheticManagementPolicies.closure(data);}
}
