package com.xksystem.logistics.tms.exceptions;

import com.xksystem.logistics.platform.RequestErrorRenderer;
import com.xksystem.logistics.tms.TmsSecurityErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class ExceptionSecurityErrors implements RequestErrorRenderer {
    private final TmsSecurityErrors delegate;
    public ExceptionSecurityErrors(TmsSecurityErrors delegate){this.delegate=delegate;}
    public boolean supports(HttpServletRequest request){return path(request.getServletPath())||path(request.getRequestURI());}
    private static boolean path(String path){return path!=null&&path.matches("/api/v1/tms/(?:exceptions(?:/.*)?|my-tasks/[^/]+/exceptions(?:/.*)?)");}
    public void render(HttpServletRequest request,HttpServletResponse response,int status,String reason,String message)throws IOException {
        response.setHeader("Cache-Control","no-store, private");delegate.render(request,response,status,reason,message);
    }
}
