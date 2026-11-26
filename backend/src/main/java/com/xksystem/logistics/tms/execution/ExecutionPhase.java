package com.xksystem.logistics.tms.execution;

/** Independent execution state; only WAITING to DEPARTED has a command in M4-01. */
public enum ExecutionPhase { WAITING, DEPARTED, IN_PROGRESS, AWAITING_COMPLETION, COMPLETED }
