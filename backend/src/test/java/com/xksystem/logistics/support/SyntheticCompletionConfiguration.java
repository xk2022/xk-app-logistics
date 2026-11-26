package com.xksystem.logistics.support;
import com.xksystem.logistics.tms.completion.CompletionPolicy;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
@TestConfiguration(proxyBeanMethods=false)
public class SyntheticCompletionConfiguration {
    @Bean @Primary public CompletionPolicy syntheticCompletionPolicy(DataSource source)throws java.sql.SQLException{return SyntheticCompletionPolicy.create(source);}
}
