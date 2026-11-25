package com.xksystem.logistics.tom;

import com.xksystem.logistics.tom.domain.FieldIssue;
import com.xksystem.logistics.tom.domain.TomDomainException;
import com.xksystem.logistics.upms.UpmsException;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Preserve the original order protocol instead of widening the UPMS envelope. */
@RestControllerAdvice(basePackageClasses = TomController.class)
public class TomErrors {
    private static final Logger LOG = LoggerFactory.getLogger(TomErrors.class);

    @ExceptionHandler(TomFailure.class)
    public ResponseEntity<TomEnvelope.Failure> expected(HttpServletRequest request, TomFailure failure) {
        return error(request, failure.status(), failure.code(), failure.getMessage(), failure.data(), failure.fieldErrors());
    }

    @ExceptionHandler(TomDomainException.class)
    public ResponseEntity<TomEnvelope.Failure> domain(HttpServletRequest request, TomDomainException failure) {
        return error(request, failure.status(), failure.code(), failure.getMessage(), null, failure.fieldErrors());
    }

    @ExceptionHandler(UpmsException.class)
    public ResponseEntity<TomEnvelope.Failure> authorization(HttpServletRequest request, UpmsException failure) {
        if (failure.status() == 401) return error(request, 401, "UNAUTHENTICATED", failure.getMessage(), null, List.of());
        if (failure.status() == 403) return error(request, 403, "FORBIDDEN", failure.getMessage(), null, List.of());
        return unexpected(request, failure);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<TomEnvelope.Failure> invalid(HttpServletRequest request, Exception ignored) {
        return error(request, 400, "VALIDATION_ERROR", "請確認請求欄位與格式", null,
                List.of(new FieldIssue("", "request", "FORMAT", "請依契約提供有效 JSON、路徑與欄位")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<TomEnvelope.Failure> unexpected(HttpServletRequest request, Exception failure) {
        // Commit-time deferred FK failures must roll back and preserve the protected-relation result too.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23503".equals(sql.getSQLState()))
                return error(request, 409, "PROTECTED_RELATION", "草稿包含不可移除的受保護關聯", null, List.of());
        }
        // SQL, parser and submitted-data diagnostics may contain private information.
        LOG.error("TOM request failed ({})", failure.getClass().getSimpleName());
        return error(request, 500, "INTERNAL_ERROR", "系統暫時無法完成操作，請稍後重試", null, List.of());
    }

    private static ResponseEntity<TomEnvelope.Failure> error(HttpServletRequest request, int status, String code,
            String message, Object data, List<FieldIssue> issues) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(TomEnvelope.error(request, code, message, data, issues));
    }
}
