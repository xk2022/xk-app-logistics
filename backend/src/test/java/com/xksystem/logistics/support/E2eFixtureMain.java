package com.xksystem.logistics.support;

import com.xksystem.logistics.upms.UpmsConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Explicit test-classpath tool, never present in the production jar or an application profile. */
public final class E2eFixtureMain {
    private E2eFixtureMain() {
    }

    public static void main(String[] args) throws Exception {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://127.0.0.1:5432/xk_logistics_e2e");
        if (!url.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/xk_logistics_e2e")) {
            throw new IllegalArgumentException("The fixture runner requires the local isolated xk_logistics_e2e database");
        }
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("An isolated database credential is required");
        }
        var encoder = new UpmsConfiguration().passwordEncoder();
        Map<String, Object> fixture = new LinkedHashMap<>();
        try (var connection = DriverManager.getConnection(url,
                System.getenv().getOrDefault("DB_USER", "xk_dev"), password)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("LOCK TABLE logistics.upms_account IN ACCESS EXCLUSIVE MODE");
                try (var count = statement.executeQuery("SELECT count(*) FROM logistics.upms_account")) {
                    count.next();
                    if (count.getLong(1) != 0) {
                        throw new IllegalStateException("The fixture runner requires an empty isolated account table");
                    }
                }
            }
            for (String project : new String[] {"chromium", "mobile-chromium"}) {
                Map<String, Object> accounts = new LinkedHashMap<>();
                for (String name : new String[] {"admin", "dispatcher", "driver"}) {
                    String role = name.toUpperCase(java.util.Locale.ROOT);
                    String username = "e2e-" + UUID.randomUUID().toString().substring(0, 16);
                    String secret = UpmsTestData.password();
                    UpmsTestData.insert(connection, encoder, username, role, secret, true, Instant.now());
                    accounts.put(name, Map.of("username", username, "password", secret));
                }
                Map<String, Object> adm = new LinkedHashMap<>();
                for (String name : new String[] {"admin", "dispatcher", "driver"}) {
                    String username = "adm-e2e-" + UUID.randomUUID().toString().substring(0, 16);
                    String secret = UpmsTestData.password();
                    UpmsTestData.insert(connection, encoder, username, name.toUpperCase(java.util.Locale.ROOT),
                            secret, false, Instant.now());
                    adm.put(name, Map.of("username", username, "password", secret));
                }
                adm.put("parameterKeys", AdmTestData.insertDefinitions(connection,
                        project.equals("chromium") ? "DESKTOP" : "MOBILE"));
                accounts.put("adm", adm);
                Map<String, Object> crm = new LinkedHashMap<>();
                for (String name : new String[] {"admin", "dispatcher", "driver"}) {
                    String username = "crm-e2e-" + UUID.randomUUID().toString().substring(0, 16);
                    String secret = UpmsTestData.password();
                    UpmsTestData.insert(connection, encoder, username, name.toUpperCase(java.util.Locale.ROOT),
                            secret, false, Instant.now());
                    crm.put(name, Map.of("username", username, "password", secret));
                }
                accounts.put("crm", crm);
                Map<String, Object> tom = new LinkedHashMap<>();
                for (String name : new String[] {"admin", "dispatcher", "driver"}) {
                    String username = "tom-e2e-" + UUID.randomUUID().toString().substring(0, 16);
                    String secret = UpmsTestData.password();
                    UpmsTestData.insert(connection, encoder, username, name.toUpperCase(java.util.Locale.ROOT),
                            secret, false, Instant.now());
                    tom.put(name, Map.of("username", username, "password", secret));
                }
                accounts.put("tom", tom);
                Map<String, Object> orderUi = new LinkedHashMap<>();
                for (String name : new String[] {"admin", "dispatcher", "driver"}) {
                    String username = "order-ui-e2e-" + UUID.randomUUID().toString().substring(0, 16);
                    String secret = UpmsTestData.password();
                    UpmsTestData.insert(connection, encoder, username, name.toUpperCase(java.util.Locale.ROOT),
                            secret, false, Instant.now());
                    orderUi.put(name, Map.of("username", username, "password", secret));
                }
                accounts.put("orderUi", orderUi);
                fixture.put(project, accounts);
            }
            Path output = Path.of(System.getenv().getOrDefault("E2E_FIXTURE_PATH", "/tmp/xk-m2-e2e-fixture.json")).toAbsolutePath();
            Files.createDirectories(output.getParent());
            Path temporary = Files.createTempFile(output.getParent(), "xk-fixture-", ".json",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(temporary, JsonMapper.builder().build().writeValueAsString(fixture));
                connection.commit();
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        System.out.println("Synthetic E2E fixture created; credentials are stored only in the private fixture file.");
    }
}
