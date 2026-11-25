package com.xksystem.logistics.tom;

import com.xksystem.logistics.tom.domain.FieldIssue;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

public final class TomEnvelope {
    private TomEnvelope() {}
    public record Success(String code,String message,Object data,String traceId) {}
    public record Failure(String code,String message,Object data,String traceId,List<FieldIssue> fieldErrors) {}
    public static Success ok(HttpServletRequest request,Object data){return new Success("OK","成功",data,trace(request));}
    public static Failure error(HttpServletRequest request,String code,String message,Object data,List<FieldIssue> issues){
        return new Failure(code,message,data,trace(request),issues);
    }
    public static String trace(HttpServletRequest request){
        Object value=request.getAttribute("REQUEST_TRACE_ID");
        if(value!=null)return value.toString();
        String trace=UUID.randomUUID().toString();request.setAttribute("REQUEST_TRACE_ID",trace);return trace;
    }
}
