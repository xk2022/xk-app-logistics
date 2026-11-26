package com.xksystem.logistics.support;
import com.xksystem.logistics.tms.exceptions.ExceptionPolicy;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
@TestConfiguration(proxyBeanMethods=false)
public class SyntheticExceptionConfiguration {
    @Bean @Primary public ExceptionPolicy syntheticExceptionPolicy(DataSource source)throws java.sql.SQLException{return SyntheticExceptionPolicy.create(source);}
}
