package com.xksystem.logistics.tms.completion;
import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tom.TaskQueries;
import java.util.List;
import java.util.Optional;
/** Independent server safety decision; no checkbox or FMS status grants formal approval. */
public interface CompletionPolicy {
    record Requirements(String evidenceBasis,int minimumCount,List<String> allowedMediaTypes,int maxBytes,long maxPixels,boolean requiresSafeReleaseConfirmation) {
        public Requirements { allowedMediaTypes=List.copyOf(allowedMediaTypes); }
    }
    PolicyMode mode();
    Optional<Requirements> requirements(TaskQueries.Source source,DriverQueries.Snapshot driver);
}
