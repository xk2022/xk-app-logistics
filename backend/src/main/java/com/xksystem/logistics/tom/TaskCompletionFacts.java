package com.xksystem.logistics.tom;
import java.util.List;
import java.util.Set;
import java.util.UUID;
/** Trusted bounded committed facts, joined to the caller transaction; no HTTP permission or dispatch qualification. */
public interface TaskCompletionFacts {
    Set<UUID> completed(UUID orderId,long sourceOrderVersion,List<UUID> taskIds);
}
