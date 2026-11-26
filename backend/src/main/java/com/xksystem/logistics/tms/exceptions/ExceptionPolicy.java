package com.xksystem.logistics.tms.exceptions;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy;
import com.xksystem.logistics.tms.execution.ExecutionDtos;
import com.xksystem.logistics.tom.TaskQueries;
import java.util.Optional;

/** Independent exception decisions, never a field/photo waiver, release or closure policy. */
public interface ExceptionPolicy {
    enum Action { REPORT, REQUEST_SUPPLEMENT, UPLOAD_SUPPLEMENT, SUBMIT_SUPPLEMENT, RESOLVE }
    record Context(TaskQueries.Source source,TmsDtos.DriverSnapshot driver,ExecutionDtos.View execution) {}
    PolicyMode mode();
    boolean allows(Action action,Context context);
    Optional<ExecutionEvidencePolicy.Requirements> requirements(Context context);
    boolean managerCanRead(TaskQueries.Source source,TmsDtos.DriverSnapshot driver);
}
