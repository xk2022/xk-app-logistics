package com.xksystem.logistics.support;

import com.xksystem.logistics.tms.DispatchEligibilityPolicy;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Opt-in integration-test import; excluded from ordinary application scanning. */
@TestConfiguration(proxyBeanMethods = false)
public class SyntheticDispatchPolicyConfiguration {
    @Bean
    @Primary
    public DispatchEligibilityPolicy syntheticDispatchEligibilityPolicy(DataSource dataSource) throws SQLException {
        return SyntheticDispatchPolicy.create(dataSource);
    }
}
