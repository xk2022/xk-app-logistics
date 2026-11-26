package com.xksystem.logistics.tms.internal;

import com.xksystem.logistics.tom.AcceptedTaskIndexSink;
import com.xksystem.logistics.tom.TaskQueries;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class TmsTaskIndex implements AcceptedTaskIndexSink {
    private final TmsStore store;TmsTaskIndex(TmsStore store){this.store=store;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void accepted(List<TaskQueries.Seed> tasks){store.index(tasks);}
}
