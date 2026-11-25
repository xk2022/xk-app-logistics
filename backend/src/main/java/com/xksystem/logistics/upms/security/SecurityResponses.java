package com.xksystem.logistics.upms.security;

import com.xksystem.logistics.upms.ApiEnvelope;
import com.xksystem.logistics.platform.RequestErrorRenderer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;

@Component
public class SecurityResponses {
    private final JsonMapper json;
    private final List<RequestErrorRenderer> renderers;
    public SecurityResponses(JsonMapper json,List<RequestErrorRenderer> renderers) { this.json=json;this.renderers=renderers; }
    public void error(HttpServletRequest request,HttpServletResponse response,int status,String reason,String message) throws IOException {
        // A rejected write after revocation can make CsrfFilter create a fresh
        // anonymous session. Mirror only that container-issued session, never
        // choose a side in a conflict or conceal rotation of authenticated state.
        if(!SessionCookieAliases.hasConflictingCookies(request)
                &&response.getHeaders("Set-Cookie").stream().anyMatch(header->header.startsWith(SessionCookieAliases.CANONICAL_NAME+"="))) {
            var session=request.getSession(false);
            if(session!=null&&session.getAttribute(UpmsSessions.AUTH)==null)
                SessionCookieAliases.mirrorCurrentSession(request,response);
        }
        for(var renderer:renderers) if(renderer.supports(request)) {renderer.render(request,response,status,reason,message);return;}
        error(response,status,message);
    }
    public void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiEnvelope.error("E0"+status, message, null)));
    }
}
