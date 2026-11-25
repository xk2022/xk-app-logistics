package com.xksystem.logistics.tom.domain;

/** Public contract location; paths are relative to DraftData, also for PUT. */
public record FieldIssue(String path, String fieldId, String ruleId, String message) {}
