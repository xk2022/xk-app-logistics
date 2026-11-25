package com.xksystem.logistics.upms;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice(basePackages={"com.xksystem.logistics.upms", "com.xksystem.logistics.adm"})
public class UpmsErrors {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(UpmsErrors.class);
    @ExceptionHandler(UpmsException.class)
    public ResponseEntity<ApiEnvelope<Object>> expected(UpmsException exception) {
        return ResponseEntity.status(exception.status()).body(ApiEnvelope.error(exception.code(), exception.getMessage(), exception.data()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiEnvelope<Object>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(ApiEnvelope.error("E0400", "請確認輸入欄位與資料格式", Map.of("request", "請提供符合格式的完整資料")));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Object>> unsupportedMethod(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(405).body(ApiEnvelope.error("E0405", "不支援此操作方法", null));
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Object>> unsupportedType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(415).body(ApiEnvelope.error("E0415", "請使用 JSON 格式提交資料", null));
    }
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ApiEnvelope<Object>> conflict(Exception exception) {
        return ResponseEntity.status(409).body(ApiEnvelope.error("E0409", "資料已更新或違反唯一性，請重新讀取後再試", null));
    }
    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ApiEnvelope<Object>> unavailable(Exception exception) {
        return ResponseEntity.status(503).body(ApiEnvelope.error("E0503", "服務暫時無法使用，請稍後重試", null));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Object>> unexpected(Exception exception) {
        // Exception messages/causes can contain SQL or submitted credentials; log only the safe type.
        log.error("UPMS request failed ({})", exception.getClass().getSimpleName());
        return ResponseEntity.status(500).body(ApiEnvelope.error("E0500", "系統暫時無法完成操作，請稍後重試", null));
    }
}
