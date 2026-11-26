package com.xksystem.logistics.upms.security;

import com.xksystem.logistics.upms.UpmsException;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class UpmsSessions implements HttpSessionListener {
    public static final String AUTH = "UPMS_AUTH";
    private final Map<UUID, LinkedHashMap<String, HttpSession>> sessions = new java.util.HashMap<>();
    @Bean
    public ServletListenerRegistrationBean<HttpSessionListener> sessionListener() {
        return new ServletListenerRegistrationBean<>(this);
    }
    public synchronized void register(UUID account, HttpSession session) {
        var active = sessions.computeIfAbsent(account, ignored -> new LinkedHashMap<>());
        active.put(session.getId(), session);
        while (active.size() > 3) {
            var oldest = active.entrySet().iterator().next();
            active.remove(oldest.getKey()); invalidate(oldest.getValue());
        }
    }
    public synchronized void revoke(UUID account) {
        var active = sessions.remove(account);
        if (active != null) new ArrayList<>(active.values()).forEach(UpmsSessions::invalidate);
    }
    @Override
    public synchronized void sessionDestroyed(HttpSessionEvent event) {
        String id = event.getSession().getId();
        sessions.values().forEach(active -> active.remove(id));
        sessions.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    public static void invalidate(HttpSession session) {
        try { session.invalidate(); } catch (IllegalStateException ignored) { /* Already invalidated. */ }
    }
    /**
     * Revalidates this request's original session after the caller acquired the canonical lock.
     * Logout uses that same lock and invalidates before releasing it. A different/new session
     * or another account's still-valid session cannot revive an already queued request.
     */
    public static void requireCurrent(HttpSession original,SessionPrincipal principal,Clock clock) {
        if(original==null||principal==null)throw UpmsException.unauthorized();
        try {
            Object value=original.getAttribute(AUTH);
            if(!(value instanceof SessionPrincipal current)||!current.id().equals(principal.id())
                    ||current.authVersion()!=principal.authVersion()
                    ||!current.authenticatedAt().equals(principal.authenticatedAt()))throw UpmsException.unauthorized();
            var now=clock.instant();
            if(!now.isBefore(current.authenticatedAt().plus(Duration.ofHours(8)))
                    ||!now.isBefore(current.lastAccessAt().plus(Duration.ofMinutes(30))))throw UpmsException.unauthorized();
        } catch(IllegalStateException invalidated) { throw UpmsException.unauthorized(); }
    }
}
