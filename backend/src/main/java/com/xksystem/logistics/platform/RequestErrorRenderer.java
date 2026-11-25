package com.xksystem.logistics.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Request-aware wire-contract port; renderers must not depend on authorization or persistence services. */
public interface RequestErrorRenderer {
    boolean supports(HttpServletRequest request);
    void render(HttpServletRequest request,HttpServletResponse response,int status,String reason,String message) throws IOException;
}
