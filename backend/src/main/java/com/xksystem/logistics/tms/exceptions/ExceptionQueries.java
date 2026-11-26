package com.xksystem.logistics.tms.exceptions;

import java.util.UUID;
/** Trusted blocker facts only. Not completion, safe-release or closure authorization. */
public interface ExceptionQueries {
    record Blocking(UUID assignmentId,long unresolvedCount) {}
    /** MANDATORY writable caller transaction and canonical/FMS/TOM ordering first; locks assignment even for count zero. */
    Blocking lockBlockingForAssignment(UUID assignmentId);
}
