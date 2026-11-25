package com.xksystem.logistics.upms;

import java.util.UUID;

public record AccountDto(UUID id, String username, String displayName, AccountRole role,
                         boolean enabled, boolean mustChangePassword, long version) {}
