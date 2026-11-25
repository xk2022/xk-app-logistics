package com.xksystem.logistics.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Synthetic database arrangement shared only by integration tests and the E2E fixture process. */
public final class UpmsTestData {
    private UpmsTestData() {
    }

    public static UUID insert(Connection connection, PasswordEncoder encoder, String username, String role,
            String password, boolean temporary, Instant now) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO logistics.upms_account
                (id, username, display_name, role, enabled, must_change_password, password_hash,
                 temporary_password_expires_at, auth_version, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, ?, ?, ?, 0, 0, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setString(2, username);
            statement.setString(3, "合成測試帳號");
            statement.setString(4, role);
            statement.setBoolean(5, temporary);
            statement.setString(6, encoder.encode(password));
            statement.setObject(7, temporary ? OffsetDateTime.ofInstant(now.plusSeconds(900), ZoneOffset.UTC) : null);
            statement.setObject(8, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.setObject(9, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.executeUpdate();
        }
        return id;
    }

    public static String password() {
        return "Synthetic!" + UUID.randomUUID();
    }
}
