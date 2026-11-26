package com.xksystem.logistics.tom.closure;
import com.xksystem.logistics.tms.confirmation.ManagementConfirmationController;
import jakarta.servlet.http.HttpServletRequest;

import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import java.sql.SQLException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={ManagementConfirmationController.class,OrderClosureController.class})
public class ClosureErrors {
    private static final Logger LOG=LoggerFactory.getLogger(ClosureErrors.class);
    @ExceptionHandler(ClosureFailure.class)
    public ResponseEntity<ApiEnvelope<Object>> expected(ClosureFailure failure){return error(failure.status(),failure.code(),failure.getMessage(),failure.data());}
    @ExceptionHandler(UpmsException.class)
    public ResponseEntity<ApiEnvelope<Object>> source(UpmsException failure) {
        int status=failure.status();
        if(status==401||status==404)return error(status,"E0"+status,status==404?"找不到授權範圍內的管理紀錄":failure.getMessage(),null);
        if(status==403)return error(403,"E0403",failure.getMessage(),Map.of("reason","FORBIDDEN"));
        return unexpected(failure,null);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ApiEnvelope<Object>> invalid(Exception ignored){return expected(ClosureFailure.invalid("request","請依契約提供有效 JSON、路徑與欄位"));}
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Object>> method(Exception ignored){return error(405,"E0405","不支援此操作方法",null);}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Object>> media(Exception ignored){return error(415,"E0415","請使用支援的請求媒體格式",null);}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Object>> unexpected(Exception failure,HttpServletRequest request) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(request!=null&&request.getMethod().equals("POST")&&cause instanceof SQLException sql&&"23503".equals(sql.getSQLState()))
            return expected(ClosureFailure.conflict("PROTECTED_RELATION","管理紀錄包含受保護關聯"));
        LOG.error("Management closure request failed ({})",failure.getClass().getSimpleName());
        return error(500,"E0500","系統暫時無法完成操作，請稍後重試",null);
    }
    private static ResponseEntity<ApiEnvelope<Object>> error(int status,String code,String message,Object data){return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private").body(ApiEnvelope.error(code,message,data));}
}
