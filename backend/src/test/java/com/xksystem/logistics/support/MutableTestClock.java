package com.xksystem.logistics.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only time control: production timeout and rate-limit values stay unchanged. */
public final class MutableTestClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-25T00:00:00Z"));

    public void advance(Duration duration) {
        now.updateAndGet(value -> value.plus(duration));
    }

    public void reset() {
        now.set(Instant.parse("2026-11-25T00:00:00Z"));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
