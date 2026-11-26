package com.xksystem.logistics.support;

import com.xksystem.logistics.tms.evidence.EvidenceStorage;
import com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods=false)
public class SyntheticEvidenceConfiguration {
    @Bean @Primary public ExecutionEvidencePolicy syntheticExecutionEvidencePolicy(DataSource source)throws java.sql.SQLException {return SyntheticEvidencePolicy.create(source);}
    @Bean @Primary public EvidenceStorage syntheticEvidenceStorage(DataSource source)throws java.sql.SQLException {return SyntheticEvidenceStorage.create(source);}
}
