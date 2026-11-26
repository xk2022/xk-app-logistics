package com.xksystem.logistics.tom.closure;
import com.xksystem.logistics.platform.RequestErrorRenderer;
import com.xksystem.logistics.tms.TmsSecurityErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Exact new paths only: the original five TOM APIs keep their own envelope. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class ClosureSecurityErrors implements RequestErrorRenderer {
    private final TmsSecurityErrors delegate;
    public ClosureSecurityErrors(TmsSecurityErrors delegate){this.delegate=delegate;}
    public boolean supports(HttpServletRequest request){return path(request.getServletPath())||path(request.getRequestURI());}
    private static boolean path(String path){return path!=null&&(path.matches("/api/v1/tms/completions(?:/[^/]+/(?:confirm|confirmation(?:/history)?))?")||path.equals("/api/v1/orders/closure-worklist")||path.matches("/api/v1/orders/[^/]+/(?:close|closure(?:/(?:tasks|history))?)"));}
    public void render(HttpServletRequest request,HttpServletResponse response,int status,String reason,String message)throws IOException{response.setHeader("Cache-Control","no-store, private");delegate.render(request,response,status,reason,message);}
}
