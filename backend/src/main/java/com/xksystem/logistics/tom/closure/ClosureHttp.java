package com.xksystem.logistics.tom.closure;

import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

/** Shared transport rules for the nine independent management operations only. */
public final class ClosureHttp {
    private ClosureHttp() {}
    public static void getOnly(HttpServletRequest request) { if (!request.getMethod().equals("GET")) throw method(); }
    public static void noQuery(HttpServletRequest request) { var query=request.getQueryString();if(query!=null&&!query.isEmpty())throw ClosureFailure.invalid("query","此操作不接受查詢參數"); }
    public static String single(HttpServletRequest request,String name,boolean required) {
        var values=Collections.list(request.getHeaders(name));
        if(values.size()>1||required&&values.size()!=1)throw ClosureFailure.invalid(name,"請提供單一有效欄位");
        return values.isEmpty()?null:values.getFirst();
    }
    public static void media(HttpServletRequest request) {
        String value=single(request,"Content-Type",false);if(value==null)throw mediaFailure();
        try {
            var actual=MediaType.parseMediaType(value);
            if(!actual.getType().equalsIgnoreCase("application")||!actual.getSubtype().equalsIgnoreCase("json"))throw mediaFailure();
            for(String parameter:actual.getParameters().keySet())if(!parameter.equalsIgnoreCase("charset"))throw mediaFailure();
            if(actual.getCharset()!=null&&!actual.getCharset().equals(StandardCharsets.UTF_8))throw mediaFailure();
        } catch(IllegalArgumentException invalid){throw mediaFailure();}
        String encoding=single(request,"Content-Encoding",false);if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw mediaFailure();
    }
    public static ClosureFailure method(){return new ClosureFailure(405,"E0405","不支援此操作方法",null);}
    public static ClosureFailure mediaFailure(){return new ClosureFailure(415,"E0415","請使用支援的請求媒體格式",null);}
    public static SessionPrincipal principal(Authentication authentication){if(authentication==null||!(authentication.getPrincipal() instanceof SessionPrincipal principal))throw UpmsException.unauthorized();return principal;}
    public static <T> ResponseEntity<ApiEnvelope<T>> json(T data){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private").body(ApiEnvelope.success(data));}
    public static <T> ResponseEntity<ApiEnvelope<T>> written(T data,boolean replayed){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store, private").header("Idempotency-Replayed",Boolean.toString(replayed)).body(ApiEnvelope.success(data));}
}
