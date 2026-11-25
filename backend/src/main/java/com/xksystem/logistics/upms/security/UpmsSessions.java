package com.xksystem.logistics.upms.security;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
}
