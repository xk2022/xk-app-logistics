package com.xksystem.logistics.tom;

import java.util.List;

/** Synchronous derived-index sink. Runs in the original successful acceptance transaction, never on replay. */
public interface AcceptedTaskIndexSink {
    void accepted(List<TaskQueries.Seed> tasks);
}
