package com.xksystem.logistics.upms.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Predicate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/** Selected raw-body routes must not trigger Servlet form parsing to look for a token. */
public class HeaderOnlyCsrfRequestHandler extends CsrfTokenRequestAttributeHandler {
    private final Predicate<HttpServletRequest> headerOnly;
    public HeaderOnlyCsrfRequestHandler(Predicate<HttpServletRequest> headerOnly){this.headerOnly=headerOnly;}
    @Override public String resolveCsrfTokenValue(HttpServletRequest request,CsrfToken token) {
        return headerOnly.test(request)?request.getHeader(token.getHeaderName()):super.resolveCsrfTokenValue(request,token);
    }
}
