package com.xksystem.logistics.upms;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/** Single-instance, bounded limiter. Keys never retain submitted usernames. */
@Component
public class LoginThrottle {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_KEYS = 10_000;
    private final Clock clock;
    private final LinkedHashMap<String, Attempts> attempts = new LinkedHashMap<>(16, .75f, true);
    public LoginThrottle(Clock clock) { this.clock = clock; }
    public synchronized boolean locked(String username) {
        Instant now = clock.instant();
        attempts.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
        var item = attempts.get(PasswordPolicy.sha256(username));
        return item == null ? attempts.size() >= MAX_KEYS
                : item.lockedUntil != null && now.isBefore(item.lockedUntil);
    }
    public synchronized void failure(String username) {
        Instant now = clock.instant();
        attempts.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
        String key = PasswordPolicy.sha256(username);
        Attempts item = attempts.get(key);
        if (item == null || !now.isBefore(item.started.plus(WINDOW))) {
            if (item == null && attempts.size() >= MAX_KEYS) return;
            item = new Attempts(now); attempts.put(key, item);
        }
        item.failures++;
        if (item.failures >= 10) item.lockedUntil = now.plus(WINDOW);
    }
    public synchronized void clear(String username) { attempts.remove(PasswordPolicy.sha256(username)); }
    private static final class Attempts {
        final Instant started; int failures; Instant lockedUntil;
        Attempts(Instant now) { started = now; }
        Instant expiresAt() { return lockedUntil == null ? started.plus(WINDOW) : lockedUntil; }
    }
}
