package com.xksystem.logistics.upms;

import com.xksystem.logistics.upms.internal.UpmsService;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import com.xksystem.logistics.upms.security.UpmsSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UpmsService service;
    private final UpmsSessions sessions;
    private final HttpSessionCsrfTokenRepository csrf;
    private final Clock clock;
    public AuthController(UpmsService service, UpmsSessions sessions, HttpSessionCsrfTokenRepository csrf, Clock clock) {
        this.service=service; this.sessions=sessions; this.csrf=csrf; this.clock=clock;
    }
    public record LoginRequest(String username, String password) {}
    public record PasswordRequest(String currentPassword, String newPassword) {}

    @GetMapping("/csrf")
    public ApiEnvelope<Map<String,String>> csrf(HttpServletRequest request) {
        var token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        return ApiEnvelope.success(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }
    @PostMapping("/login")
    public ApiEnvelope<AccountDto> login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        var result=service.login(input.username(), input.password());
        var old=request.getSession(false);
        if (old != null) UpmsSessions.invalidate(old);
        var session=request.getSession(true);
        request.changeSessionId();
        var now=clock.instant();
        session.setMaxInactiveInterval(30*60);
        session.setAttribute(UpmsSessions.AUTH, new SessionPrincipal(result.account().id(), result.authVersion(), now, now));
        sessions.register(result.account().id(), session);
        csrf.saveToken(csrf.generateToken(request), request, response);
        response.setHeader("Cache-Control", "no-store");
        return ApiEnvelope.success(result.account());
    }
    @GetMapping("/me")
    public ApiEnvelope<AccountDto> me(Authentication authentication) { return ApiEnvelope.success(service.current(principal(authentication))); }
    @PostMapping("/password")
    public ApiEnvelope<Void> password(@RequestBody PasswordRequest input, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        service.changePassword(principal(authentication), input.currentPassword(), input.newPassword());
        freshAnonymousSession(request, response);
        return ApiEnvelope.success(null);
    }
    @PostMapping("/logout")
    public ApiEnvelope<Void> logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        service.logout(principal(authentication));
        freshAnonymousSession(request, response);
        return ApiEnvelope.success(null);
    }
    private void freshAnonymousSession(HttpServletRequest request, HttpServletResponse response) {
        var session=request.getSession(false);
        if (session != null) UpmsSessions.invalidate(session);
        csrf.saveToken(csrf.generateToken(request), request, response);
        response.setHeader("Cache-Control", "no-store");
    }
    static SessionPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) throw UpmsException.unauthorized();
        return principal;
    }
}
