package com.xksystem.logistics.tom.domain;

import java.util.List;

public final class TomDomainException extends RuntimeException {
    private final int status;
    private final String code;
    private final List<FieldIssue> fieldErrors;

    public TomDomainException(int status, String code, String message, List<FieldIssue> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public TomDomainException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public int status() { return status; }
    public String code() { return code; }
    public List<FieldIssue> fieldErrors() { return fieldErrors; }

    static TomDomainException invalid(String path, String fieldId, String message) {
        return new TomDomainException(400, "VALIDATION_ERROR", "請確認草稿資料與欄位格式",
                List.of(new FieldIssue(path, fieldId, "FORMAT", message)));
    }
}
