package com.xksystem.logistics.tom;

import com.xksystem.logistics.platform.RequestErrorRenderer;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TomSecurityErrors implements RequestErrorRenderer {
    private final JsonMapper json;
    public TomSecurityErrors(JsonMapper json){this.json=json;}
    @Override public boolean supports(HttpServletRequest request){String path=request.getServletPath();return path.equals("/api/v1/orders")||path.startsWith("/api/v1/orders/");}
    @Override public void render(HttpServletRequest request,HttpServletResponse response,int status,String reason,String message) throws IOException {
        String code=reason;
        if(status>=500){status=500;code="INTERNAL_ERROR";message="系統暫時無法完成操作，請稍後重試";}
        if("CSRF_INVALID".equals(reason)) {
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            if(authentication==null||!(authentication.getPrincipal() instanceof SessionPrincipal)){status=401;code="UNAUTHENTICATED";}
        }
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(TomEnvelope.error(request,code,message,null,List.of())));
    }
}
