package com.xksystem.logistics.tms.evidence;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import com.xksystem.logistics.tom.TaskQueries;
import java.util.List;
import java.util.Optional;

/** Server-owned policy port. A next transition rule and private read permission are separate decisions. */
public interface ExecutionEvidencePolicy {
    record Requirements(int minimumCount,List<String> allowedMediaTypes,int maxBytes,long maxPixels) {
        public Requirements { allowedMediaTypes=List.copyOf(allowedMediaTypes); }
    }
    PolicyMode mode();
    Optional<Requirements> requirements(TaskQueries.Source source,DriverQueries.Snapshot driver,ExecutionPhase phase);
    boolean canRead(TaskQueries.Source source,DriverQueries.Snapshot driver);
}
