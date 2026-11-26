package com.xksystem.logistics.tms;

import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import java.sql.SQLException;
import java.util.Map;
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

@RestControllerAdvice(basePackageClasses=TmsController.class)
public class TmsErrors {
    private static final Logger LOG=LoggerFactory.getLogger(TmsErrors.class);
    @ExceptionHandler(TmsFailure.class)
    public ResponseEntity<ApiEnvelope<Object>> expected(TmsFailure failure) {
        return error(failure.status(),failure.code(),failure.getMessage(),failure.data());
    }
    @ExceptionHandler(UpmsException.class)
    public ResponseEntity<ApiEnvelope<Object>> source(UpmsException failure) {
        int status=failure.status();
        if(status==403)return error(403,"E0403",failure.getMessage(),Map.of("reason","FORBIDDEN"));
        if(status==401||status==404)return error(status,"E0"+status,failure.getMessage(),null);
        if(status==400)return error(400,"E0400",failure.getMessage(),Map.of("reason","VALIDATION_ERROR","fields",
                failure.data() instanceof Map<?,?>?failure.data():Map.of("request","請確認來源欄位")));
        return unexpected(failure);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,MissingServletRequestParameterException.class,HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ApiEnvelope<Object>> invalid(Exception ignored) {
        return expected(TmsFailure.invalid("request","請依契約提供有效 JSON、路徑與欄位"));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Object>> method(Exception ignored){return error(405,"E0405","不支援此操作方法",null);}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Object>> media(Exception ignored){return error(415,"E0415","請使用 JSON 格式提交資料",null);}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Object>> unexpected(Exception failure) {
        // Commit-time deferred failures also must return the agreed code after rollback.
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SQLException sql&&"23503".equals(sql.getSQLState()))
                return expected(TmsFailure.conflict("PROTECTED_RELATION","派工包含不可變更的受保護關聯"));
        }
        // SQL/parser diagnostics and submitted values must never reach a report or API.
        LOG.error("TMS request failed ({})",failure.getClass().getSimpleName());
        return error(500,"E0500","系統暫時無法完成操作，請稍後重試",null);
    }
    private static ResponseEntity<ApiEnvelope<Object>> error(int status,String code,String message,Object data) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiEnvelope.error(code,message,data));
    }
}
