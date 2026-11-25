package com.xksystem.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Flyway flyway;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void initializesEmptyPostgresWithPlatformUpmsAdmCrmAndNoSeedAccountsCustomersOrParameters() {
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '1'", String.class))
                .isEqualTo("1");
        assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname = 'logistics'", String.class))
                .containsExactlyInAnyOrder("flyway_schema_history", "upms_lock", "upms_account", "upms_audit",
                        "adm_dict_category", "adm_dict_item", "adm_parameter_definition", "adm_parameter", "adm_change_log",
                        "crm_customer", "crm_contact", "crm_address");
        assertThat(jdbc.queryForObject("select count(*) from logistics.upms_account", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from logistics.upms_audit", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from logistics.upms_lock where id=1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '2'", String.class))
                .isEqualTo("2");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '3'", String.class))
                .isEqualTo("3");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '4'", String.class))
                .isEqualTo("4");
        assertThat(jdbc.queryForList("select code from logistics.adm_dict_category order by sort_order", String.class))
                .containsExactly("ORDER_TYPE", "CONTAINER_TYPE", "TASK_KIND", "VEHICLE_TYPE");
        assertThat(jdbc.queryForList("select code from logistics.adm_dict_item order by sort_order", String.class))
                .containsExactly("IMPORT", "EXPORT", "LOCAL");
        assertThat(jdbc.queryForObject("select count(*) from logistics.adm_dict_item where not read_only", Long.class)).isZero();
        for (String table : new String[] {"adm_parameter_definition", "adm_parameter", "adm_change_log", "crm_customer", "crm_contact", "crm_address"}) {
            assertThat(jdbc.queryForObject("select count(*) from logistics." + table, Long.class)).isZero();
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void exposesOnlySafeHealthAndStatus() throws Exception {
        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json.readTree(health.body())).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
        assertThat(health.headers().firstValue("X-Request-Id")).isPresent();

        HttpResponse<String> response = get("/api/v1/status");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("0000");
        assertThat(body.get("message").asString()).isEqualTo("成功");
        assertThat(body.get("data").get("service").asString()).isEqualTo("xk-logistics");
        assertThat(body.get("data").get("status").asString()).isEqualTo("UP");
        assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/auth/csrf").statusCode()).isEqualTo(403);
    }

    @Test
    void upgradesExistingM1V1ToV2WithoutSeedAccounts() {
        String databaseName = "xk_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway oldVersion = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("1").load();
            assertThat(oldVersion.migrate().migrationsExecuted).isEqualTo(1);
            Flyway upgraded = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("2").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
            var database = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
            assertThat(database.queryForObject("select count(*) from logistics.upms_account", Long.class)).isZero();
            assertThat(database.queryForObject("select count(*) from logistics.upms_audit", Long.class)).isZero();
            assertThat(database.queryForList("select version from logistics.flyway_schema_history where success and version is not null order by installed_rank", String.class))
                    .containsExactly("1", "2");
        } finally {
            jdbc.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
        }
    }

    @Test
    void upgradesV2ToV3PreservingExistingUpmsAccountsAndPasswordHashes() throws Exception {
        String databaseName = "xk_adm_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("2").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(2);
            java.util.UUID account;
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
                account = com.xksystem.logistics.support.UpmsTestData.insert(connection,
                        new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder(),
                        "synthetic-upgrade-admin", "ADMIN", com.xksystem.logistics.support.UpmsTestData.password(),
                        false, java.time.Instant.now());
            }
            var database = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
            String hash = database.queryForObject("select password_hash from logistics.upms_account where id=?", String.class, account);
            Flyway upgraded = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("3").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(database.queryForObject("select password_hash from logistics.upms_account where id=?", String.class, account))
                    .isEqualTo(hash);
            assertThat(database.queryForObject("select username from logistics.upms_account where id=?", String.class, account))
                    .isEqualTo("synthetic-upgrade-admin");
            assertThat(database.queryForObject("select count(*) from logistics.upms_account", Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("select count(*) from logistics.adm_dict_category", Long.class)).isEqualTo(4);
            assertThat(database.queryForObject("select count(*) from logistics.adm_dict_item", Long.class)).isEqualTo(3);
            assertThat(database.queryForObject("select count(*) from logistics.adm_parameter_definition", Long.class)).isZero();
            assertThat(database.queryForObject("select count(*) from logistics.adm_parameter", Long.class)).isZero();
            assertThat(database.queryForObject("select count(*) from logistics.adm_change_log", Long.class)).isZero();
        } finally {
            jdbc.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
        }
    }

    @Test
    void upgradesV3ToV4PreservingUpmsAccountsAndAdmDataWithoutSeedingCustomers() throws Exception {
        String databaseName = "xk_crm_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("3").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(3);
            java.util.UUID account;
            java.util.Map<String, String> definitions;
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
                account = com.xksystem.logistics.support.UpmsTestData.insert(connection,
                        new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder(),
                        "synthetic-crm-upgrade", "ADMIN", com.xksystem.logistics.support.UpmsTestData.password(),
                        false, java.time.Instant.now());
                definitions = com.xksystem.logistics.support.AdmTestData.insertDefinitions(connection, "UPGRADE");
            }
            var database = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
            String hash = database.queryForObject("SELECT password_hash FROM logistics.upms_account WHERE id=?", String.class, account);
            java.util.UUID category = java.util.UUID.randomUUID();
            java.util.UUID item = java.util.UUID.randomUUID();
            java.util.UUID parameter = java.util.UUID.randomUUID();
            database.update("""
                    INSERT INTO logistics.adm_dict_category(id,code,name,enabled,sort_order,read_only,version,created_at,updated_at)
                    VALUES (?,'SYNTHETIC_UPGRADE','合成升級分類',true,99,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, category);
            database.update("""
                    INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at)
                    VALUES (?,?,'SYNTHETIC_ITEM','合成升級項目',true,0,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, item, category);
            database.update("""
                    INSERT INTO logistics.adm_parameter(id,parameter_key,name,value_type,value_text,description,enabled,version,created_at,updated_at)
                    VALUES (?,?,'合成升級參數','TEXT','upgrade','Synthetic upgrade fixture',true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, parameter, definitions.get("text"));
            database.update("""
                    INSERT INTO logistics.adm_change_log(id,target_type,target_id,action,actor_id,actor_username,occurred_at,before_value,after_value)
                    VALUES (?,'CATEGORY',?,'CREATE',?,'synthetic-crm-upgrade',CURRENT_TIMESTAMP,NULL,'{"code":"SYNTHETIC_UPGRADE"}'::jsonb)
                    """, java.util.UUID.randomUUID(), category, account);
            Flyway upgraded = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(database.queryForObject("SELECT password_hash FROM logistics.upms_account WHERE id=?", String.class, account)).isEqualTo(hash);
            assertThat(database.queryForObject("SELECT name FROM logistics.adm_dict_category WHERE id=?", String.class, category)).isEqualTo("合成升級分類");
            assertThat(database.queryForObject("SELECT label FROM logistics.adm_dict_item WHERE id=?", String.class, item)).isEqualTo("合成升級項目");
            assertThat(database.queryForObject("SELECT value_text FROM logistics.adm_parameter WHERE id=?", String.class, parameter)).isEqualTo("upgrade");
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.adm_change_log WHERE actor_id=?", Long.class, account)).isEqualTo(1);
            for (String table : new String[] {"crm_customer", "crm_contact", "crm_address"}) {
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class)).isZero();
            }
        } finally {
            jdbc.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
