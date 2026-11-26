package com.xksystem.logistics.tms;

import com.xksystem.logistics.platform.RequestErrorRenderer;
import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TmsSecurityErrors implements RequestErrorRenderer {
    private final JsonMapper json;
    public TmsSecurityErrors(JsonMapper json){this.json=json;}
    @Override public boolean supports(HttpServletRequest request) {
        String path=request.getServletPath(),rawPath=request.getRequestURI();
        return path.equals("/api/v1/tms")||path.startsWith("/api/v1/tms/")
                ||rawPath.equals("/api/v1/tms")||rawPath.startsWith("/api/v1/tms/");
    }
    @Override public void render(HttpServletRequest request,HttpServletResponse response,int status,String reason,String message)throws IOException {
        if("CSRF_INVALID".equals(reason)) {
            var auth=SecurityContextHolder.getContext().getAuthentication();
            if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal))status=401;
        }
        if(status>=500){status=500;message="系統暫時無法完成操作，請稍後重試";}
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        Object data=status==400?Map.of("reason","VALIDATION_ERROR","fields",Map.of("request","請確認請求路徑與格式"))
                :status==403?Map.of("reason","CSRF_INVALID".equals(reason)?"CSRF_INVALID":"FORBIDDEN"):null;
        response.getWriter().write(json.writeValueAsString(ApiEnvelope.error("E0"+status,message,data)));
    }
}
