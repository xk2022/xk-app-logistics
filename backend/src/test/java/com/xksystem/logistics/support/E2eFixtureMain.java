package com.xksystem.logistics.support;

import com.xksystem.logistics.upms.UpmsConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
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
            UUID vehicleTypeCategoryId;
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM logistics.adm_dict_category WHERE code='VEHICLE_TYPE' AND enabled=true");
                    var category = statement.executeQuery()) {
                if (!category.next()) {
                    throw new IllegalStateException("Synthetic vehicle types require an enabled VEHICLE_TYPE category");
                }
                vehicleTypeCategoryId = category.getObject(1, UUID.class);
            }
            UUID driverLicenseTypeCategoryId;
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM logistics.adm_dict_category WHERE code='DRIVER_LICENSE_TYPE' AND enabled=true");
                    var category = statement.executeQuery()) {
                if (!category.next()) {
                    throw new IllegalStateException("Synthetic license types require an enabled DRIVER_LICENSE_TYPE category");
                }
                driverLicenseTypeCategoryId = category.getObject(1, UUID.class);
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
                Map<String, Object> fms = new LinkedHashMap<>();
                for (String name : new String[] {"admin", "dispatcher", "driver"}) {
                    String username = "fms-e2e-" + UUID.randomUUID().toString().substring(0, 16);
                    String secret = UpmsTestData.password();
                    UpmsTestData.insert(connection, encoder, username, name.toUpperCase(java.util.Locale.ROOT),
                            secret, false, Instant.now());
                    fms.put(name, Map.of("username", username, "password", secret));
                }
                fms.put("vehicleTypeCategoryId", vehicleTypeCategoryId.toString());
                fms.put("vehicleTypes", insertVehicleTypes(connection, vehicleTypeCategoryId,
                        project.equals("chromium") ? "DESKTOP" : "MOBILE"));
                accounts.put("fms", fms);
                Map<String, Object> driverFlow = new LinkedHashMap<>();
                String driverAccountPrefix = "driver-" + (project.equals("chromium") ? "d-" : "m-")
                        + UUID.randomUUID().toString().substring(0, 8) + "-";
                for (String name : new String[] {"admin", "dispatcher", "driverA", "driverB", "driverC", "temporary"}) {
                    String username = driverAccountPrefix + name.toLowerCase(java.util.Locale.ROOT);
                    String secret = UpmsTestData.password();
                    String role = switch (name) {
                        case "admin" -> "ADMIN";
                        case "dispatcher" -> "DISPATCHER";
                        default -> "DRIVER";
                    };
                    UUID id = UpmsTestData.insert(connection, encoder, username, role,
                            secret, name.equals("temporary"), Instant.now());
                    driverFlow.put(name, Map.of("id", id.toString(), "username", username, "password", secret));
                }
                driverFlow.put("accountSearchPrefix", driverAccountPrefix);
                driverFlow.put("licenseTypeCategoryId", driverLicenseTypeCategoryId.toString());
                driverFlow.put("licenseTypes", insertDriverLicenseTypes(connection, driverLicenseTypeCategoryId,
                        project.equals("chromium") ? "DESKTOP" : "MOBILE"));
                accounts.put("driverFlow", driverFlow);
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

    private static Map<String, Map<String, String>> insertVehicleTypes(Connection connection,
            UUID categoryId, String suffix) throws Exception {
        Map<String, Map<String, String>> vehicleTypes = new LinkedHashMap<>();
        for (String name : new String[] {"a", "b", "c"}) {
            UUID id = UUID.randomUUID();
            String code = "FMS_" + suffix + "_" + name.toUpperCase(java.util.Locale.ROOT);
            String kind = switch (name) {
                case "a" -> "車頭";
                case "b" -> "拖板";
                default -> "貨車";
            };
            String label = "合成測試" + kind + " " + suffix + " " + name.toUpperCase(java.util.Locale.ROOT);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO logistics.adm_dict_item
                        (id,category_id,code,label,enabled,sort_order,read_only,created_at,updated_at)
                    VALUES (?,?,?,?,true,?,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """)) {
                statement.setObject(1, id);
                statement.setObject(2, categoryId);
                statement.setString(3, code);
                statement.setString(4, label);
                statement.setInt(5, switch (name) {
                    case "a" -> 10;
                    case "b" -> 20;
                    default -> 30;
                });
                statement.executeUpdate();
            }
            vehicleTypes.put(name, Map.of("id", id.toString(), "code", code, "label", label));
        }
        return vehicleTypes;
    }

    private static Map<String, Map<String, String>> insertDriverLicenseTypes(Connection connection,
            UUID categoryId, String suffix) throws Exception {
        Map<String, Map<String, String>> licenseTypes = new LinkedHashMap<>();
        for (String name : new String[] {"a", "b"}) {
            UUID id = UUID.randomUUID();
            String code = "DRIVER_" + suffix + "_" + name.toUpperCase(java.util.Locale.ROOT);
            String label = "合成測試駕照類別 " + suffix + " " + name.toUpperCase(java.util.Locale.ROOT);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO logistics.adm_dict_item
                        (id,category_id,code,label,enabled,sort_order,read_only,created_at,updated_at)
                    VALUES (?,?,?,?,true,?,false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """)) {
                statement.setObject(1, id);
                statement.setObject(2, categoryId);
                statement.setString(3, code);
                statement.setString(4, label);
                statement.setInt(5, name.equals("a") ? 10 : 20);
                statement.executeUpdate();
            }
            licenseTypes.put(name, Map.of("id", id.toString(), "code", code, "label", label));
        }
        return licenseTypes;
    }
}
