package com.xksystem.logistics.upms.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record SessionPrincipal(UUID id, long authVersion, Instant authenticatedAt, Instant lastAccessAt)
        implements Serializable {}
