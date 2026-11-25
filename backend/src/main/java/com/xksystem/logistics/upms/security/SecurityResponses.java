package com.xksystem.logistics.upms.security;

import com.xksystem.logistics.upms.ApiEnvelope;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityResponses {
    private final JsonMapper json;
    public SecurityResponses(JsonMapper json) { this.json=json; }
    public void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiEnvelope.error("E0"+status, message, null)));
    }
}
