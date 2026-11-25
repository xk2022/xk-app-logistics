package com.xksystem.logistics.upms;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record ApiEnvelope<T>(String code, String message, T data, OffsetDateTime timestamp) {
    public static <T> ApiEnvelope<T> success(T data) {
        return new ApiEnvelope<>("0000", "成功", data, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    }
    public static ApiEnvelope<Object> error(String code, String message, Object data) {
        return new ApiEnvelope<>(code, message, data, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    }
}
