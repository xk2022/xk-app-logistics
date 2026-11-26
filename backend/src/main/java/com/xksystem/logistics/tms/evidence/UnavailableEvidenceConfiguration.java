package com.xksystem.logistics.tms.evidence;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import com.xksystem.logistics.tom.TaskQueries;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** No profile/property/provider path in the normal artifact approves evidence storage or rules. */
@Configuration
public class UnavailableEvidenceConfiguration {
    @Bean public ExecutionEvidencePolicy executionEvidencePolicy() {
        return new ExecutionEvidencePolicy() {
            public PolicyMode mode(){return PolicyMode.UNCONFIRMED;}
            public Optional<Requirements> requirements(TaskQueries.Source source,DriverQueries.Snapshot driver,ExecutionPhase phase){return Optional.empty();}
            public boolean canRead(TaskQueries.Source source,DriverQueries.Snapshot driver){return false;}
        };
    }
    @Bean public EvidenceStorage evidenceStorage() {
        return new EvidenceStorage() {
            public PolicyMode mode(){return PolicyMode.UNCONFIRMED;}
            public void store(UUID objectId,byte[] bytes,String sha256){throw EvidenceFailure.conflict("POLICY_NOT_CONFIRMED","尚未核准照片儲存政策");}
            public byte[] load(UUID objectId){throw EvidenceFailure.conflict("POLICY_NOT_CONFIRMED","尚未核准照片讀取政策");}
        };
    }
}
