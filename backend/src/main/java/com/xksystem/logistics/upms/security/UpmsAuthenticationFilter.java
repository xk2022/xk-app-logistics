package com.xksystem.logistics.upms.security;

import com.xksystem.logistics.upms.AccountDto;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.internal.UpmsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

public class UpmsAuthenticationFilter extends OncePerRequestFilter {
    private final UpmsService service;
    private final Clock clock;
    private final SecurityResponses responses;
    public UpmsAuthenticationFilter(UpmsService service, Clock clock, SecurityResponses responses) {
        this.service=service; this.clock=clock; this.responses=responses;
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().equals("/actuator/health") || request.getServletPath().equals("/api/v1/status");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var session=request.getSession(false);
        if (session != null) {
            try {
                var principal=(SessionPrincipal)session.getAttribute(UpmsSessions.AUTH);
                if (principal != null) {
                    var now=clock.instant();
                    if (!now.isBefore(principal.authenticatedAt().plus(Duration.ofHours(8)))
                            || !now.isBefore(principal.lastAccessAt().plus(Duration.ofMinutes(30)))) {
                        UpmsSessions.invalidate(session);
                    } else {
                        AccountDto account=service.current(principal);
                        // The sole authority is the current persisted role, never a client supplied value.
                        var authentication=UsernamePasswordAuthenticationToken.authenticated(principal, null,
                                List.of(new SimpleGrantedAuthority("ROLE_"+account.role().name())));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        session.setAttribute(UpmsSessions.AUTH, new SessionPrincipal(principal.id(), principal.authVersion(), principal.authenticatedAt(), now));
                        request.setAttribute("UPMS_ACCOUNT", account);
                    }
                }
            } catch (UpmsException | IllegalStateException exception) {
                UpmsSessions.invalidate(session);
                SecurityContextHolder.clearContext();
            } catch (DataAccessException | CannotCreateTransactionException unavailable) {
                SecurityContextHolder.clearContext();
                responses.error(response, 503, "服務暫時無法使用，請稍後重試");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
