package com.xksystem.logistics.upms.security;

import com.xksystem.logistics.upms.AccountDto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

public class UpmsAccessFilter extends OncePerRequestFilter {
    private static final Set<String> LIMITED = Set.of("/api/v1/auth/csrf", "/api/v1/auth/me",
            "/api/v1/auth/password", "/api/v1/auth/logout");
    private final SecurityResponses responses;
    public UpmsAccessFilter(SecurityResponses responses) { this.responses=responses; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path=request.getServletPath();
        boolean upms=path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/upms/") || path.startsWith("/api/v1/adm/") || path.equals("/api/v1/adm")
                || path.startsWith("/api/v1/crm/") || path.equals("/api/v1/crm");
        upms=upms||path.equals("/api/v1/orders")||path.startsWith("/api/v1/orders/");
        if (upms && !request.isSecure()) {
            responses.error(request,response,403,"FORBIDDEN","請使用 HTTPS 安全連線"); return;
        }
        var account=(AccountDto)request.getAttribute("UPMS_ACCOUNT");
        if (account != null && account.mustChangePassword() && upms && !LIMITED.contains(path)) {
            responses.error(request,response,403,"FORBIDDEN","首次登入或帳號復原後須先變更密碼"); return;
        }
        chain.doFilter(request, response);
    }
}
