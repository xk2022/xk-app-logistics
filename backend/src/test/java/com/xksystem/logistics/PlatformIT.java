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
            .asCompatibleSubstituteFor("postgres")).withStartupAttempts(3);

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
                        "crm_customer", "crm_contact", "crm_address", "order_core", "tom_order", "cargo_item", "transport_task",
                        "task_dependency", "order_draft_revision", "api_idempotency", "fms_vehicle", "fms_driver", "order_acceptance_event",
                        "tms_task_dispatch", "tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency",
                        "tms_execution", "tms_execution_event", "tms_execution_command", "tms_execution_evidence",
                        "tms_execution_evidence_object", "tms_execution_event_evidence", "tms_execution_evidence_command",
                        "tms_exception", "tms_exception_event", "tms_exception_command", "tms_exception_supplement_request",
                        "tms_exception_supplement_evidence", "tms_exception_event_evidence", "tms_completion", "tms_completion_command",
                        "tms_completion_confirmation", "tms_completion_confirmation_command", "tom_order_closure", "tom_order_closure_task", "tom_order_closure_command");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname='logistics'", Long.class)).isEqualTo(47);
        assertThat(jdbc.queryForObject("select count(*) from logistics.upms_account", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from logistics.upms_audit", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from logistics.upms_lock where id=1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '2'", String.class))
                .isEqualTo("2");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '3'", String.class))
                .isEqualTo("3");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '4'", String.class))
                .isEqualTo("4");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '5'", String.class))
                .isEqualTo("5");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '6'", String.class))
                .isEqualTo("6");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '7'", String.class)).isEqualTo("7");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '8'", String.class)).isEqualTo("8");
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '9'", String.class)).isEqualTo("9");
        assertThat(jdbc.queryForList("select version from logistics.flyway_schema_history where success and version is not null order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
        assertThat(jdbc.queryForList("select code from logistics.adm_dict_category order by sort_order", String.class))
                .containsExactly("ORDER_TYPE", "CONTAINER_TYPE", "TASK_KIND", "VEHICLE_TYPE", "DRIVER_LICENSE_TYPE");
        assertThat(jdbc.queryForList("select code from logistics.adm_dict_item order by sort_order", String.class))
                .containsExactly("IMPORT", "EXPORT", "LOCAL");
        assertThat(jdbc.queryForObject("select count(*) from logistics.adm_dict_item where not read_only", Long.class)).isZero();
        for (String table : new String[] {"adm_parameter_definition", "adm_parameter", "adm_change_log", "crm_customer", "crm_contact", "crm_address",
                "order_core", "tom_order", "cargo_item", "transport_task", "task_dependency", "order_draft_revision", "api_idempotency", "fms_vehicle", "fms_driver", "order_acceptance_event",
                "tms_task_dispatch", "tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency",
                "tms_execution", "tms_execution_event", "tms_execution_command", "tms_execution_evidence",
                "tms_execution_evidence_object", "tms_execution_event_evidence", "tms_execution_evidence_command",
                "tms_exception", "tms_exception_event", "tms_exception_command", "tms_exception_supplement_request",
                "tms_exception_supplement_evidence", "tms_exception_event_evidence", "tms_completion", "tms_completion_command",
                "tms_completion_confirmation", "tms_completion_confirmation_command", "tom_order_closure", "tom_order_closure_task", "tom_order_closure_command"}) {
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
                    .schemas("logistics").defaultSchema("logistics").target("4").load();
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

    @Test
    void upgradesV4ToV5PreservingExistingUpmsAdmAndCrmDataWithoutSeedingOrders() throws Exception {
        String databaseName = "xk_tom_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("4").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(4);
            java.util.UUID actor;
            java.util.Map<String, String> definitions;
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
                actor = com.xksystem.logistics.support.UpmsTestData.insert(connection,
                        new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder(), "synthetic-tom-upgrade", "ADMIN",
                        com.xksystem.logistics.support.UpmsTestData.password(), false, java.time.Instant.now());
                definitions = com.xksystem.logistics.support.AdmTestData.insertDefinitions(connection, "TOM_UPGRADE");
            }
            var database = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
            String hash = database.queryForObject("SELECT password_hash FROM logistics.upms_account WHERE id=?", String.class, actor);
            java.util.UUID customer = java.util.UUID.randomUUID(), contact = java.util.UUID.randomUUID(), address = java.util.UUID.randomUUID();
            database.update("""
                    INSERT INTO logistics.crm_customer(id,customer_code,name,enabled,version,created_at,updated_at,created_by,updated_by)
                    VALUES (?,'SYNTHETIC_TOM_UPGRADE','合成待升級客戶',true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                    """, customer, actor, actor);
            database.update("""
                    INSERT INTO logistics.crm_contact(id,customer_id,name,enabled,version,created_at,updated_at,created_by,updated_by)
                    VALUES (?,?,'合成待升級聯絡人',true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                    """, contact, customer, actor, actor);
            database.update("""
                    INSERT INTO logistics.crm_address(id,customer_id,address,enabled,version,created_at,updated_at,created_by,updated_by)
                    VALUES (?,?,'合成待升級地址',true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                    """, address, customer, actor, actor);
            database.update("""
                    INSERT INTO logistics.adm_parameter(id,parameter_key,name,value_type,value_text,description,enabled,version,created_at,updated_at)
                    VALUES (?,?,'合成待升級參數','TEXT','upgrade','Synthetic upgrade fixture',true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, java.util.UUID.randomUUID(), definitions.get("text"));
            Flyway upgraded = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("5").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(hash.equals(database.queryForObject("SELECT password_hash FROM logistics.upms_account WHERE id=?", String.class, actor))).isTrue();
            assertThat(database.queryForObject("SELECT name FROM logistics.crm_customer WHERE id=?", String.class, customer)).isEqualTo("合成待升級客戶");
            assertThat(database.queryForObject("SELECT customer_id FROM logistics.crm_contact WHERE id=?", java.util.UUID.class, contact)).isEqualTo(customer);
            assertThat(database.queryForObject("SELECT address FROM logistics.crm_address WHERE id=?", String.class, address)).isEqualTo("合成待升級地址");
            assertThat(database.queryForObject("SELECT value_text FROM logistics.adm_parameter WHERE parameter_key=?", String.class, definitions.get("text"))).isEqualTo("upgrade");
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.adm_dict_category", Long.class)).isEqualTo(4);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.adm_dict_item", Long.class)).isEqualTo(3);
            for (String table : new String[] {"order_core", "tom_order", "cargo_item", "transport_task", "task_dependency", "order_draft_revision", "api_idempotency"}) {
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class)).isZero();
            }
        } finally {
            jdbc.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
        }
    }
    @Test
    void upgradesV5ToV6PreservingEveryM2AggregateRevisionReplayAndMasterRecordWithoutVehicleSeeds() throws Exception {
        String databaseName="xk_fms_upgrade_test";
        jdbc.execute("CREATE DATABASE "+databaseName);
        String url=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+databaseName);
        try{
            Flyway old=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).schemas("logistics").defaultSchema("logistics").target("5").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(5);
            var database=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));
            java.util.UUID actor;java.util.Map<String,String> definitions;
            try(var connection=java.sql.DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword())){
                actor=com.xksystem.logistics.support.UpmsTestData.insert(connection,new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder(),"synthetic-fms-upgrade","ADMIN",com.xksystem.logistics.support.UpmsTestData.password(),false,java.time.Instant.now());
                definitions=com.xksystem.logistics.support.AdmTestData.insertDefinitions(connection,"FMS_UPGRADE");
            }
            var snapshot=(tools.jackson.databind.node.ObjectNode)com.xksystem.logistics.support.TomContract.example("schemaPositive","DEMO-LOCAL-CONTRACT-response").get("value").get("data");
            snapshot.put("createdBy",actor.toString()).put("updatedBy",actor.toString()).put("createdAt","2026-11-17T08:00:00Z").put("updatedAt","2026-11-17T08:00:00Z").put("remark","合成\u0000V5升級資料");
            ((tools.jackson.databind.node.ObjectNode)snapshot.get("items").get(0)).put("grossWeight","1200");
            com.xksystem.logistics.support.TomContract.assertSchema("OrderDraftRead",snapshot);
            java.util.UUID customer=java.util.UUID.fromString(snapshot.get("customerId").asString()),order=java.util.UUID.fromString(snapshot.get("id").asString());
            var at=java.time.OffsetDateTime.parse("2026-11-17T08:00:00Z");
            database.update("INSERT INTO logistics.upms_audit(id,actor,target_id,action,reason,occurred_at) VALUES (?,?,?,'LOGIN_SUCCESS','Synthetic V5 upgrade fixture',?)",java.util.UUID.randomUUID(),"synthetic-fms-upgrade",actor,at);
            java.util.UUID category=java.util.UUID.randomUUID(),item=java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_category(id,code,name,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,'SYNTHETIC_V5_CATALOG','合成升級分類',true,99,false,0,?,?)",category,at,at);
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,?,'SYNTHETIC_V5_ITEM','合成升級項目',false,0,false,0,?,?)",item,category,at,at);
            database.update("INSERT INTO logistics.adm_parameter(id,parameter_key,name,value_type,value_text,description,enabled,version,created_at,updated_at) VALUES (?,?,'合成升級參數','TEXT','upgrade','Synthetic fixture',true,0,?,?)",java.util.UUID.randomUUID(),definitions.get("text"),at,at);
            database.update("INSERT INTO logistics.adm_change_log(id,target_type,target_id,action,actor_id,actor_username,occurred_at,before_value,after_value) VALUES (?,'CATEGORY',?,'CREATE',?,'synthetic-fms-upgrade',?,NULL,'{\"code\":\"SYNTHETIC_V5_CATALOG\"}'::jsonb)",java.util.UUID.randomUUID(),category,actor,at);
            database.update("INSERT INTO logistics.crm_customer(id,customer_code,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,'SYNTHETIC_FMS_UPGRADE',?,true,0,?,?,?,?)",customer,snapshot.get("customerName").asString(),at,at,actor,actor);
            database.update("INSERT INTO logistics.crm_contact(id,customer_id,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成升級聯絡人',true,0,?,?,?,?)",java.util.UUID.randomUUID(),customer,at,at,actor,actor);
            database.update("INSERT INTO logistics.crm_address(id,customer_id,address,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成升級地址',true,0,?,?,?,?)",java.util.UUID.randomUUID(),customer,at,at,actor,actor);
            database.update("INSERT INTO logistics.order_core(id,company_scope,order_no,lifecycle_status,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,'OPEN',?,?,?,?)",order,snapshot.get("orderNo").asString(),at,at,actor,actor);
            database.update("INSERT INTO logistics.tom_order(order_id,order_type,customer_id,customer_name,acceptance_status,version,remark,shipping_present,created_at,updated_at,created_by,updated_by) VALUES (?,'LOCAL',?,?,'DRAFT',0,?,false,?,?,?,?)",order,customer,snapshot.get("customerName").asString(),snapshot.get("remark").asString().getBytes(java.nio.charset.StandardCharsets.UTF_8),at,at,actor,actor);
            for(JsonNode cargo:snapshot.get("items"))database.update("INSERT INTO logistics.cargo_item(id,order_id,client_ref,input_position,sequence,item_kind,package_qty,gross_weight,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,0,1,'CARGO',10,1200,?,?,?,?)",java.util.UUID.fromString(cargo.get("id").asString()),order,cargo.get("clientRef").asString(),at,at,actor,actor);
            int position=0;
            for(JsonNode task:snapshot.get("tasks")){
                database.update("INSERT INTO logistics.transport_task(id,task_no,order_id,item_id,client_ref,input_position,task_kind,pickup_name,pickup_address,delivery_name,delivery_address,contact_present,planned_start_at,planned_end_at,planned_start_wire,planned_end_wire,required_task,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,false,?,?,?,?,true,?,?,?,?)",java.util.UUID.fromString(task.get("id").asString()),task.get("taskNo").asString(),order,java.util.UUID.fromString(task.get("itemId").asString()),task.get("clientRef").asString(),position++,bytes(task,"taskKind"),bytes(task,"pickupName"),bytes(task,"pickupAddress"),bytes(task,"deliveryName"),bytes(task,"deliveryAddress"),java.time.OffsetDateTime.parse(task.get("plannedStartAt").asString()),java.time.OffsetDateTime.parse(task.get("plannedEndAt").asString()),task.get("plannedStartAt").asString(),task.get("plannedEndAt").asString(),at,at,actor,actor);
                int predecessorPosition=0;
                for(JsonNode predecessor:task.get("predecessorTaskIds"))database.update("INSERT INTO logistics.task_dependency(order_id,task_id,predecessor_task_id,input_position,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",order,java.util.UUID.fromString(task.get("id").asString()),java.util.UUID.fromString(predecessor.asString()),predecessorPosition++,at,at,actor,actor);
            }
            String saved=json.writeValueAsString(snapshot);
            var originalRequest=(tools.jackson.databind.node.ObjectNode)com.xksystem.logistics.support.TomContract.example("graphCases","DEMO-LOCAL-CONTRACT").get("draft");
            originalRequest.put("remark",snapshot.get("remark").asString());
            String requestHash=com.xksystem.logistics.tom.domain.DraftCodec.canonicalHash(originalRequest);
            database.update("INSERT INTO logistics.order_draft_revision(id,order_id,aggregate_version,snapshot,request_trace_id,created_at,updated_at,created_by,updated_by) VALUES (?,?,0,?::json,'synthetic-upgrade-trace',?,?,?,?)",java.util.UUID.randomUUID(),order,saved,at,at,actor,actor);
            database.update("INSERT INTO logistics.api_idempotency(id,company_scope,actor_id,http_method,canonical_path,idempotency_key,request_hash,order_id,original_status,original_data,original_location,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,'POST','/api/v1/orders','synthetic-v5-upgrade',?,?,201,?::json,?,?,?,?,?)",java.util.UUID.randomUUID(),actor,requestHash,order,saved,"/api/v1/orders/"+order,at,at,actor,actor);
            var tableOrders=new java.util.LinkedHashMap<String,String>();
            for(String table:new String[]{"upms_lock","upms_account","upms_audit","adm_dict_category","adm_dict_item","adm_parameter_definition","adm_parameter","adm_change_log","crm_customer","crm_contact","crm_address","order_core","cargo_item","transport_task","order_draft_revision","api_idempotency"})tableOrders.put(table,"id");
            tableOrders.put("adm_parameter_definition","parameter_key");tableOrders.put("tom_order","order_id");tableOrders.put("task_dependency","order_id,task_id,predecessor_task_id");
            var before=new java.util.LinkedHashMap<String,String>();tableOrders.forEach((table,ordering)->before.put(table,tableDigest(database,table,ordering)));
            Flyway upgrade=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).schemas("logistics").defaultSchema("logistics").target("6").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table,ordering)->assertThat(tableDigest(database,table,ordering)).as("Preserved every existing V5 row in %s",table).isEqualTo(before.get(table)));
            assertThat(database.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics'",Long.class)).isEqualTo(20);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.fms_vehicle",Long.class)).isZero();
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.adm_dict_item i JOIN logistics.adm_dict_category c ON c.id=i.category_id WHERE c.code='VEHICLE_TYPE'",Long.class)).isZero();
            assertThat(json.readTree(database.queryForObject("SELECT snapshot::text FROM logistics.order_draft_revision WHERE order_id=?",String.class,order))).isEqualTo(snapshot);
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.api_idempotency WHERE order_id=?",String.class,order))).isEqualTo(snapshot);
        }finally{jdbc.execute("DROP DATABASE "+databaseName+" WITH (FORCE)");}
    }

    @Test
    void upgradesV6ToV7PreservingAllExistingM2AndVehicleRowsAndAddingOnlyEmptyLicenseCategory() throws Exception {
        String databaseName="xk_driver_upgrade_test";
        jdbc.execute("CREATE DATABASE "+databaseName);
        String url=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+databaseName);
        try{
            Flyway old=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).schemas("logistics").defaultSchema("logistics").target("6").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(6);
            var database=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));
            java.util.UUID actor;java.util.Map<String,String> definitions;
            try(var connection=java.sql.DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword())){
                actor=com.xksystem.logistics.support.UpmsTestData.insert(connection,new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder(),"synthetic-fms-upgrade","ADMIN",com.xksystem.logistics.support.UpmsTestData.password(),false,java.time.Instant.now());
                definitions=com.xksystem.logistics.support.AdmTestData.insertDefinitions(connection,"FMS_UPGRADE");
            }
            var snapshot=(tools.jackson.databind.node.ObjectNode)com.xksystem.logistics.support.TomContract.example("schemaPositive","DEMO-LOCAL-CONTRACT-response").get("value").get("data");
            snapshot.put("createdBy",actor.toString()).put("updatedBy",actor.toString()).put("createdAt","2026-11-17T08:00:00Z").put("updatedAt","2026-11-17T08:00:00Z").put("remark","合成\u0000V5升級資料");
            ((tools.jackson.databind.node.ObjectNode)snapshot.get("items").get(0)).put("grossWeight","1200");
            com.xksystem.logistics.support.TomContract.assertSchema("OrderDraftRead",snapshot);
            java.util.UUID customer=java.util.UUID.fromString(snapshot.get("customerId").asString()),order=java.util.UUID.fromString(snapshot.get("id").asString());
            var at=java.time.OffsetDateTime.parse("2026-11-17T08:00:00Z");
            database.update("INSERT INTO logistics.upms_audit(id,actor,target_id,action,reason,occurred_at) VALUES (?,?,?,'LOGIN_SUCCESS','Synthetic V5 upgrade fixture',?)",java.util.UUID.randomUUID(),"synthetic-fms-upgrade",actor,at);
            java.util.UUID category=java.util.UUID.randomUUID(),item=java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_category(id,code,name,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,'SYNTHETIC_V5_CATALOG','合成升級分類',true,99,false,0,?,?)",category,at,at);
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,?,'SYNTHETIC_V5_ITEM','合成升級項目',false,0,false,0,?,?)",item,category,at,at);
            database.update("INSERT INTO logistics.adm_parameter(id,parameter_key,name,value_type,value_text,description,enabled,version,created_at,updated_at) VALUES (?,?,'合成升級參數','TEXT','upgrade','Synthetic fixture',true,0,?,?)",java.util.UUID.randomUUID(),definitions.get("text"),at,at);
            database.update("INSERT INTO logistics.adm_change_log(id,target_type,target_id,action,actor_id,actor_username,occurred_at,before_value,after_value) VALUES (?,'CATEGORY',?,'CREATE',?,'synthetic-fms-upgrade',?,NULL,'{\"code\":\"SYNTHETIC_V5_CATALOG\"}'::jsonb)",java.util.UUID.randomUUID(),category,actor,at);
            database.update("INSERT INTO logistics.crm_customer(id,customer_code,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,'SYNTHETIC_FMS_UPGRADE',?,true,0,?,?,?,?)",customer,snapshot.get("customerName").asString(),at,at,actor,actor);
            database.update("INSERT INTO logistics.crm_contact(id,customer_id,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成升級聯絡人',true,0,?,?,?,?)",java.util.UUID.randomUUID(),customer,at,at,actor,actor);
            database.update("INSERT INTO logistics.crm_address(id,customer_id,address,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成升級地址',true,0,?,?,?,?)",java.util.UUID.randomUUID(),customer,at,at,actor,actor);
            database.update("INSERT INTO logistics.order_core(id,company_scope,order_no,lifecycle_status,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,'OPEN',?,?,?,?)",order,snapshot.get("orderNo").asString(),at,at,actor,actor);
            database.update("INSERT INTO logistics.tom_order(order_id,order_type,customer_id,customer_name,acceptance_status,version,remark,shipping_present,created_at,updated_at,created_by,updated_by) VALUES (?,'LOCAL',?,?,'DRAFT',0,?,false,?,?,?,?)",order,customer,snapshot.get("customerName").asString(),snapshot.get("remark").asString().getBytes(java.nio.charset.StandardCharsets.UTF_8),at,at,actor,actor);
            for(JsonNode cargo:snapshot.get("items"))database.update("INSERT INTO logistics.cargo_item(id,order_id,client_ref,input_position,sequence,item_kind,package_qty,gross_weight,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,0,1,'CARGO',10,1200,?,?,?,?)",java.util.UUID.fromString(cargo.get("id").asString()),order,cargo.get("clientRef").asString(),at,at,actor,actor);
            int position=0;
            for(JsonNode task:snapshot.get("tasks")){
                database.update("INSERT INTO logistics.transport_task(id,task_no,order_id,item_id,client_ref,input_position,task_kind,pickup_name,pickup_address,delivery_name,delivery_address,contact_present,planned_start_at,planned_end_at,planned_start_wire,planned_end_wire,required_task,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,false,?,?,?,?,true,?,?,?,?)",java.util.UUID.fromString(task.get("id").asString()),task.get("taskNo").asString(),order,java.util.UUID.fromString(task.get("itemId").asString()),task.get("clientRef").asString(),position++,bytes(task,"taskKind"),bytes(task,"pickupName"),bytes(task,"pickupAddress"),bytes(task,"deliveryName"),bytes(task,"deliveryAddress"),java.time.OffsetDateTime.parse(task.get("plannedStartAt").asString()),java.time.OffsetDateTime.parse(task.get("plannedEndAt").asString()),task.get("plannedStartAt").asString(),task.get("plannedEndAt").asString(),at,at,actor,actor);
                int predecessorPosition=0;
                for(JsonNode predecessor:task.get("predecessorTaskIds"))database.update("INSERT INTO logistics.task_dependency(order_id,task_id,predecessor_task_id,input_position,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",order,java.util.UUID.fromString(task.get("id").asString()),java.util.UUID.fromString(predecessor.asString()),predecessorPosition++,at,at,actor,actor);
            }
            String saved=json.writeValueAsString(snapshot);
            var originalRequest=(tools.jackson.databind.node.ObjectNode)com.xksystem.logistics.support.TomContract.example("graphCases","DEMO-LOCAL-CONTRACT").get("draft");
            originalRequest.put("remark",snapshot.get("remark").asString());
            String requestHash=com.xksystem.logistics.tom.domain.DraftCodec.canonicalHash(originalRequest);
            database.update("INSERT INTO logistics.order_draft_revision(id,order_id,aggregate_version,snapshot,request_trace_id,created_at,updated_at,created_by,updated_by) VALUES (?,?,0,?::json,'synthetic-upgrade-trace',?,?,?,?)",java.util.UUID.randomUUID(),order,saved,at,at,actor,actor);
            database.update("INSERT INTO logistics.api_idempotency(id,company_scope,actor_id,http_method,canonical_path,idempotency_key,request_hash,order_id,original_status,original_data,original_location,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,'POST','/api/v1/orders','synthetic-v5-upgrade',?,?,201,?::json,?,?,?,?,?)",java.util.UUID.randomUUID(),actor,requestHash,order,saved,"/api/v1/orders/"+order,at,at,actor,actor);
            java.util.UUID vehicleType=java.util.UUID.randomUUID(),vehicle=java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) SELECT ?,id,'SYNTHETIC_V6_TRUCK','合成舊車種',false,1,false,0,?,? FROM logistics.adm_dict_category WHERE code='VEHICLE_TYPE'",vehicleType,at,at);
            database.update("INSERT INTO logistics.fms_vehicle(id,plate_no,vehicle_type_id,capacity_ton,registration_date,inspection_due_date,administrative_status,version,created_at,created_by,updated_at,updated_by) VALUES (?,'SYNTHETIC-V6-VEHICLE',?,12.3400,DATE '1500-01-02',DATE '9999-12-31','INACTIVE',7,?,?,?,?)",vehicle,vehicleType,at,actor,at,actor);
            var tableOrders=new java.util.LinkedHashMap<String,String>();
            for(String table:new String[]{"upms_lock","upms_account","upms_audit","adm_dict_category","adm_dict_item","adm_parameter_definition","adm_parameter","adm_change_log","crm_customer","crm_contact","crm_address","order_core","cargo_item","transport_task","order_draft_revision","api_idempotency","fms_vehicle"})tableOrders.put(table,"id");
            tableOrders.put("adm_parameter_definition","parameter_key");tableOrders.put("tom_order","order_id");tableOrders.put("task_dependency","order_id,task_id,predecessor_task_id");
            var before=new java.util.LinkedHashMap<String,String>();tableOrders.forEach((table,ordering)->before.put(table,tableDigest(database,table,ordering,table.equals("adm_dict_category")?" WHERE code <> 'DRIVER_LICENSE_TYPE'":"")));
            Flyway upgrade=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).schemas("logistics").defaultSchema("logistics").target("7").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table,ordering)->assertThat(tableDigest(database,table,ordering,table.equals("adm_dict_category")?" WHERE code <> 'DRIVER_LICENSE_TYPE'":"")).as("Preserved every existing V6 row in %s",table).isEqualTo(before.get(table)));
            assertThat(database.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics'",Long.class)).isEqualTo(21);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.fms_vehicle",Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.fms_driver",Long.class)).isZero();
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.adm_dict_category WHERE code='DRIVER_LICENSE_TYPE' AND enabled AND NOT read_only",Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT capacity_ton::text FROM logistics.fms_vehicle WHERE id=?",String.class,vehicle)).isEqualTo("12.3400");
            assertThat(database.queryForObject("SELECT registration_date::text FROM logistics.fms_vehicle WHERE id=?",String.class,vehicle)).isEqualTo("1500-01-02");
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.adm_dict_item i JOIN logistics.adm_dict_category c ON c.id=i.category_id WHERE c.code='DRIVER_LICENSE_TYPE'",Long.class)).isZero();
            assertThat(json.readTree(database.queryForObject("SELECT snapshot::text FROM logistics.order_draft_revision WHERE order_id=?",String.class,order))).isEqualTo(snapshot);
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.api_idempotency WHERE order_id=?",String.class,order))).isEqualTo(snapshot);
        }finally{jdbc.execute("DROP DATABASE "+databaseName+" WITH (FORCE)");}
    }

    @Test
    void upgradesV7ToV8PreservingEveryPriorDomainRowWithoutInventingHistoricalAcceptanceEvents() throws Exception {
        String databaseName="xk_acceptance_upgrade_test";
        jdbc.execute("CREATE DATABASE "+databaseName);
        String url=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/"+databaseName);
        try{
            Flyway old=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).schemas("logistics").defaultSchema("logistics").target("7").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(7);
            var database=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,postgres.getUsername(),postgres.getPassword()));
            java.util.UUID actor;java.util.Map<String,String> definitions;
            try(var connection=java.sql.DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword())){
                actor=com.xksystem.logistics.support.UpmsTestData.insert(connection,new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder(),"synthetic-fms-upgrade","ADMIN",com.xksystem.logistics.support.UpmsTestData.password(),false,java.time.Instant.now());
                definitions=com.xksystem.logistics.support.AdmTestData.insertDefinitions(connection,"FMS_UPGRADE");
            }
            var snapshot=(tools.jackson.databind.node.ObjectNode)com.xksystem.logistics.support.TomContract.example("schemaPositive","DEMO-LOCAL-CONTRACT-response").get("value").get("data");
            snapshot.put("createdBy",actor.toString()).put("updatedBy",actor.toString()).put("createdAt","2026-11-17T08:00:00Z").put("updatedAt","2026-11-17T08:00:00Z").put("remark","合成\u0000V5升級資料").put("acceptanceStatus","ACCEPTED");
            ((tools.jackson.databind.node.ObjectNode)snapshot.get("items").get(0)).put("grossWeight","1200");
            com.xksystem.logistics.support.TomContract.assertSchema("OrderDraftRead",snapshot);
            java.util.UUID customer=java.util.UUID.fromString(snapshot.get("customerId").asString()),order=java.util.UUID.fromString(snapshot.get("id").asString());
            var at=java.time.OffsetDateTime.parse("2026-11-17T08:00:00Z");
            database.update("INSERT INTO logistics.upms_audit(id,actor,target_id,action,reason,occurred_at) VALUES (?,?,?,'LOGIN_SUCCESS','Synthetic V5 upgrade fixture',?)",java.util.UUID.randomUUID(),"synthetic-fms-upgrade",actor,at);
            java.util.UUID category=java.util.UUID.randomUUID(),item=java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_category(id,code,name,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,'SYNTHETIC_V5_CATALOG','合成升級分類',true,99,false,0,?,?)",category,at,at);
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,?,'SYNTHETIC_V5_ITEM','合成升級項目',false,0,false,0,?,?)",item,category,at,at);
            database.update("INSERT INTO logistics.adm_parameter(id,parameter_key,name,value_type,value_text,description,enabled,version,created_at,updated_at) VALUES (?,?,'合成升級參數','TEXT','upgrade','Synthetic fixture',true,0,?,?)",java.util.UUID.randomUUID(),definitions.get("text"),at,at);
            database.update("INSERT INTO logistics.adm_change_log(id,target_type,target_id,action,actor_id,actor_username,occurred_at,before_value,after_value) VALUES (?,'CATEGORY',?,'CREATE',?,'synthetic-fms-upgrade',?,NULL,'{\"code\":\"SYNTHETIC_V5_CATALOG\"}'::jsonb)",java.util.UUID.randomUUID(),category,actor,at);
            database.update("INSERT INTO logistics.crm_customer(id,customer_code,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,'SYNTHETIC_FMS_UPGRADE',?,true,0,?,?,?,?)",customer,snapshot.get("customerName").asString(),at,at,actor,actor);
            database.update("INSERT INTO logistics.crm_contact(id,customer_id,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成升級聯絡人',true,0,?,?,?,?)",java.util.UUID.randomUUID(),customer,at,at,actor,actor);
            database.update("INSERT INTO logistics.crm_address(id,customer_id,address,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成升級地址',true,0,?,?,?,?)",java.util.UUID.randomUUID(),customer,at,at,actor,actor);
            database.update("INSERT INTO logistics.order_core(id,company_scope,order_no,lifecycle_status,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,'OPEN',?,?,?,?)",order,snapshot.get("orderNo").asString(),at,at,actor,actor);
            database.update("INSERT INTO logistics.tom_order(order_id,order_type,customer_id,customer_name,acceptance_status,version,remark,shipping_present,created_at,updated_at,created_by,updated_by) VALUES (?,'LOCAL',?,?,'ACCEPTED',0,?,false,?,?,?,?)",order,customer,snapshot.get("customerName").asString(),snapshot.get("remark").asString().getBytes(java.nio.charset.StandardCharsets.UTF_8),at,at,actor,actor);
            for(JsonNode cargo:snapshot.get("items"))database.update("INSERT INTO logistics.cargo_item(id,order_id,client_ref,input_position,sequence,item_kind,package_qty,gross_weight,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,0,1,'CARGO',10,1200,?,?,?,?)",java.util.UUID.fromString(cargo.get("id").asString()),order,cargo.get("clientRef").asString(),at,at,actor,actor);
            int position=0;
            for(JsonNode task:snapshot.get("tasks")){
                database.update("INSERT INTO logistics.transport_task(id,task_no,order_id,item_id,client_ref,input_position,task_kind,pickup_name,pickup_address,delivery_name,delivery_address,contact_present,planned_start_at,planned_end_at,planned_start_wire,planned_end_wire,required_task,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,false,?,?,?,?,true,?,?,?,?)",java.util.UUID.fromString(task.get("id").asString()),task.get("taskNo").asString(),order,java.util.UUID.fromString(task.get("itemId").asString()),task.get("clientRef").asString(),position++,bytes(task,"taskKind"),bytes(task,"pickupName"),bytes(task,"pickupAddress"),bytes(task,"deliveryName"),bytes(task,"deliveryAddress"),java.time.OffsetDateTime.parse(task.get("plannedStartAt").asString()),java.time.OffsetDateTime.parse(task.get("plannedEndAt").asString()),task.get("plannedStartAt").asString(),task.get("plannedEndAt").asString(),at,at,actor,actor);
                int predecessorPosition=0;
                for(JsonNode predecessor:task.get("predecessorTaskIds"))database.update("INSERT INTO logistics.task_dependency(order_id,task_id,predecessor_task_id,input_position,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",order,java.util.UUID.fromString(task.get("id").asString()),java.util.UUID.fromString(predecessor.asString()),predecessorPosition++,at,at,actor,actor);
            }
            String saved=json.writeValueAsString(snapshot);
            var originalRequest=(tools.jackson.databind.node.ObjectNode)com.xksystem.logistics.support.TomContract.example("graphCases","DEMO-LOCAL-CONTRACT").get("draft");
            originalRequest.put("remark",snapshot.get("remark").asString());
            String requestHash=com.xksystem.logistics.tom.domain.DraftCodec.canonicalHash(originalRequest);
            database.update("INSERT INTO logistics.order_draft_revision(id,order_id,aggregate_version,snapshot,request_trace_id,created_at,updated_at,created_by,updated_by) VALUES (?,?,0,?::json,'synthetic-upgrade-trace',?,?,?,?)",java.util.UUID.randomUUID(),order,saved,at,at,actor,actor);
            database.update("INSERT INTO logistics.api_idempotency(id,company_scope,actor_id,http_method,canonical_path,idempotency_key,request_hash,order_id,original_status,original_data,original_location,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,'POST','/api/v1/orders','synthetic-v5-upgrade',?,?,201,?::json,?,?,?,?,?)",java.util.UUID.randomUUID(),actor,requestHash,order,saved,"/api/v1/orders/"+order,at,at,actor,actor);
            java.util.UUID vehicleType=java.util.UUID.randomUUID(),vehicle=java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) SELECT ?,id,'SYNTHETIC_V6_TRUCK','合成舊車種',false,1,false,0,?,? FROM logistics.adm_dict_category WHERE code='VEHICLE_TYPE'",vehicleType,at,at);
            database.update("INSERT INTO logistics.fms_vehicle(id,plate_no,vehicle_type_id,capacity_ton,registration_date,inspection_due_date,administrative_status,version,created_at,created_by,updated_at,updated_by) VALUES (?,'SYNTHETIC-V6-VEHICLE',?,12.3400,DATE '1500-01-02',DATE '9999-12-31','INACTIVE',7,?,?,?,?)",vehicle,vehicleType,at,actor,at,actor);
            java.util.UUID licenseType=java.util.UUID.randomUUID(),driver=java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) SELECT ?,id,'SYNTHETIC_V7_LICENSE','合成舊證照類別',false,1,false,0,?,? FROM logistics.adm_dict_category WHERE code='DRIVER_LICENSE_TYPE'",licenseType,at,at);
            java.util.UUID driverAccount;
            try(var connection=java.sql.DriverManager.getConnection(url,postgres.getUsername(),postgres.getPassword())){driverAccount=com.xksystem.logistics.support.UpmsTestData.insert(connection,new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder(),"synthetic-v7-driver","DRIVER",com.xksystem.logistics.support.UpmsTestData.password(),false,at.toInstant());}
            database.update("INSERT INTO logistics.fms_driver(id,driver_code,name,phone,email,account_id,employment_status,duty_status,license_type_id,license_reference,license_due_date,version,created_at,created_by,updated_at,updated_by) VALUES (?,'SYNTHETIC-V7-DRIVER','合成舊司機','合成分機','描述性聯絡',?,'LEFT','ON_DUTY',?,'合成舊證照參考',DATE '1500-01-02',7,?,?,?,?)",driver,driverAccount,licenseType,at,actor,at,actor);
            var tableOrders=new java.util.LinkedHashMap<String,String>();
            for(String table:new String[]{"upms_lock","upms_account","upms_audit","adm_dict_category","adm_dict_item","adm_parameter_definition","adm_parameter","adm_change_log","crm_customer","crm_contact","crm_address","order_core","cargo_item","transport_task","order_draft_revision","api_idempotency","fms_vehicle","fms_driver"})tableOrders.put(table,"id");
            tableOrders.put("adm_parameter_definition","parameter_key");tableOrders.put("tom_order","order_id");tableOrders.put("task_dependency","order_id,task_id,predecessor_task_id");
            var before=new java.util.LinkedHashMap<String,String>();tableOrders.forEach((table,ordering)->before.put(table,tableDigest(database,table,ordering)));
            Flyway upgrade=Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).schemas("logistics").defaultSchema("logistics").target("8").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table,ordering)->assertThat(tableDigest(database,table,ordering)).as("Preserved every existing V7 row in %s",table).isEqualTo(before.get(table)));
            assertThat(database.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics'",Long.class)).isEqualTo(22);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.fms_vehicle",Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.fms_driver",Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.order_acceptance_event",Long.class)).isZero();
            assertThat(database.queryForObject("SELECT account_id FROM logistics.fms_driver WHERE id=?",java.util.UUID.class,driver)).isEqualTo(driverAccount);
            assertThat(database.queryForObject("SELECT license_due_date::text FROM logistics.fms_driver WHERE id=?",String.class,driver)).isEqualTo("1500-01-02");
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.adm_dict_category WHERE code='DRIVER_LICENSE_TYPE' AND enabled AND NOT read_only",Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT capacity_ton::text FROM logistics.fms_vehicle WHERE id=?",String.class,vehicle)).isEqualTo("12.3400");
            assertThat(database.queryForObject("SELECT registration_date::text FROM logistics.fms_vehicle WHERE id=?",String.class,vehicle)).isEqualTo("1500-01-02");
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.adm_dict_item i JOIN logistics.adm_dict_category c ON c.id=i.category_id WHERE c.code='DRIVER_LICENSE_TYPE'",Long.class)).isEqualTo(1);
            assertThat(json.readTree(database.queryForObject("SELECT snapshot::text FROM logistics.order_draft_revision WHERE order_id=?",String.class,order))).isEqualTo(snapshot);
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.api_idempotency WHERE order_id=?",String.class,order))).isEqualTo(snapshot);
        }finally{jdbc.execute("DROP DATABASE "+databaseName+" WITH (FORCE)");}
    }

    @Test
    void upgradesV8ToV9PreservingEveryPriorDomainRowAndIndexingOnlyOpenAcceptedTasks() throws Exception {
        String databaseName = "xk_logistics_e2e";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("8").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(8);
            var database = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    url, postgres.getUsername(), postgres.getPassword()));
            java.util.UUID actor, driverAccount;
            java.util.Map<String, String> definitions;
            String adminPassword = com.xksystem.logistics.support.UpmsTestData.password();
            var at = java.time.OffsetDateTime.parse("2026-11-17T08:00:00.123456Z");
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
                var encoder = new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder();
                actor = com.xksystem.logistics.support.UpmsTestData.insert(connection, encoder,
                        "synthetic-v8-upgrade-admin", "ADMIN", adminPassword, false, at.toInstant());
                driverAccount = com.xksystem.logistics.support.UpmsTestData.insert(connection, encoder,
                        "synthetic-v8-upgrade-driver", "DRIVER", com.xksystem.logistics.support.UpmsTestData.password(), false, at.toInstant());
                definitions = com.xksystem.logistics.support.AdmTestData.insertDefinitions(connection, "TMS_UPGRADE");
            }
            database.update("INSERT INTO logistics.upms_audit(id,actor,target_id,action,reason,occurred_at) VALUES (?,?,?,'LOGIN_SUCCESS','Synthetic V8 upgrade fixture',?)",
                    java.util.UUID.randomUUID(), "synthetic-v8-upgrade-admin", actor, at);
            java.util.UUID category = java.util.UUID.randomUUID(), item = java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_category(id,code,name,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,'SYNTHETIC_V8_CATALOG','合成升級分類',true,99,false,0,?,?)",
                    category, at, at);
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,?,'SYNTHETIC_V8_ITEM','合成升級項目',false,0,false,0,?,?)",
                    item, category, at, at);
            database.update("INSERT INTO logistics.adm_parameter(id,parameter_key,name,value_type,value_text,description,enabled,version,created_at,updated_at) VALUES (?,?,'合成升級參數','TEXT','upgrade','Synthetic fixture',true,0,?,?)",
                    java.util.UUID.randomUUID(), definitions.get("text"), at, at);
            database.update("INSERT INTO logistics.adm_change_log(id,target_type,target_id,action,actor_id,actor_username,occurred_at,before_value,after_value) VALUES (?,'CATEGORY',?,'CREATE',?,'synthetic-v8-upgrade-admin',?,NULL,'{\"code\":\"SYNTHETIC_V8_CATALOG\"}'::jsonb)",
                    java.util.UUID.randomUUID(), category, actor, at);
            java.util.UUID customer = java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.crm_customer(id,customer_code,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,'SYNTHETIC_V8_UPGRADE','合成歷史客戶',false,7,?,?,?,?)",
                    customer, at, at, actor, actor);
            database.update("INSERT INTO logistics.crm_contact(id,customer_id,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成升級聯絡人',true,0,?,?,?,?)",
                    java.util.UUID.randomUUID(), customer, at, at, actor, actor);
            database.update("INSERT INTO logistics.crm_address(id,customer_id,address,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成升級地址',true,0,?,?,?,?)",
                    java.util.UUID.randomUUID(), customer, at, at, actor, actor);
            JsonNode accepted = seedV8UpgradeOrder(database, actor, customer, at, "OPEN", "ACCEPTED", "ACCEPTED");
            JsonNode draft = seedV8UpgradeOrder(database, actor, customer, at, "OPEN", "DRAFT", "DRAFT");
            JsonNode closed = seedV8UpgradeOrder(database, actor, customer, at, "CLOSED", "ACCEPTED", "CLOSED");
            java.util.UUID vehicleType = java.util.UUID.randomUUID(), vehicle = java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) SELECT ?,id,'SYNTHETIC_V8_TRUCK','合成舊車種',false,1,false,0,?,? FROM logistics.adm_dict_category WHERE code='VEHICLE_TYPE'",
                    vehicleType, at, at);
            database.update("INSERT INTO logistics.fms_vehicle(id,plate_no,vehicle_type_id,capacity_ton,registration_date,inspection_due_date,administrative_status,version,created_at,created_by,updated_at,updated_by) VALUES (?,'SYNTHETIC-V8-VEHICLE',?,12.3400,DATE '1500-01-02',DATE '9999-12-31','INACTIVE',7,?,?,?,?)",
                    vehicle, vehicleType, at, actor, at, actor);
            java.util.UUID licenseType = java.util.UUID.randomUUID(), driver = java.util.UUID.randomUUID();
            database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) SELECT ?,id,'SYNTHETIC_V8_LICENSE','合成舊證照類別',false,1,false,0,?,? FROM logistics.adm_dict_category WHERE code='DRIVER_LICENSE_TYPE'",
                    licenseType, at, at);
            database.update("INSERT INTO logistics.fms_driver(id,driver_code,name,phone,email,account_id,employment_status,duty_status,license_type_id,license_reference,license_due_date,version,created_at,created_by,updated_at,updated_by) VALUES (?,'SYNTHETIC-V8-DRIVER','合成舊司機','合成分機','描述性聯絡',?,'LEFT','ON_DUTY',?,'合成舊證照參考',DATE '1500-01-02',7,?,?,?,?)",
                    driver, driverAccount, licenseType, at, actor, at, actor);

            var tableOrders = new java.util.LinkedHashMap<String, String>();
            for (String table : new String[] {"upms_lock", "upms_account", "upms_audit", "adm_dict_category", "adm_dict_item",
                    "adm_parameter", "adm_change_log", "crm_customer", "crm_contact", "crm_address", "order_core",
                    "cargo_item", "transport_task", "order_draft_revision", "api_idempotency", "fms_vehicle", "fms_driver", "order_acceptance_event"})
                tableOrders.put(table, "id");
            tableOrders.put("adm_parameter_definition", "parameter_key");
            tableOrders.put("tom_order", "order_id");
            tableOrders.put("task_dependency", "order_id,task_id,predecessor_task_id");
            assertThat(tableOrders).hasSize(21);
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics' AND tablename<>'flyway_schema_history'", String.class))
                    .containsExactlyInAnyOrderElementsOf(tableOrders.keySet());
            var before = new java.util.LinkedHashMap<String, String>();
            tableOrders.forEach((table, ordering) -> {
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                        .as("The preservation fixture populates the existing V8 table %s", table).isPositive();
                before.put(table, tableDigest(database, table, ordering));
            });
            var expectedTasks = new java.util.ArrayList<String>();
            for (JsonNode task : accepted.get("tasks")) expectedTasks.add(task.get("id").asString());
            expectedTasks.sort(String::compareTo);

            Flyway upgrade = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("9").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table, ordering) -> assertThat(tableDigest(database, table, ordering))
                    .as("Preserved every existing V8 row in %s", table).isEqualTo(before.get(table)));
            assertThat(database.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics'", Long.class)).isEqualTo(27);
            assertThat(database.queryForList("SELECT version FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
            assertThat(database.queryForList("SELECT task_id::text FROM logistics.tms_task_dispatch ORDER BY task_id", String.class))
                    .containsExactlyElementsOf(expectedTasks);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_task_dispatch WHERE order_id=? AND generation=0 AND source_order_version=1 AND company_scope='SINGLE_COMPANY' AND order_no=?",
                    Long.class, java.util.UUID.fromString(accepted.get("id").asString()), accepted.get("orderNo").asString())).isEqualTo(expectedTasks.size());
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_task_dispatch d JOIN logistics.transport_task t ON (t.order_id,t.id)=(d.order_id,d.task_id) WHERE d.task_no=t.task_no", Long.class))
                    .isEqualTo(expectedTasks.size());
            for (JsonNode excluded : java.util.List.of(draft, closed))
                assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_task_dispatch WHERE order_id=?", Long.class,
                        java.util.UUID.fromString(excluded.get("id").asString()))).isZero();
            for (String table : java.util.List.of("tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency"))
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                        .as("V9 does not invent historical business records in %s", table).isZero();
            assertThat(database.queryForObject("SELECT capacity_ton::text FROM logistics.fms_vehicle WHERE id=?", String.class, vehicle)).isEqualTo("12.3400");
            assertThat(database.queryForObject("SELECT license_due_date::text FROM logistics.fms_driver WHERE id=?", String.class, driver)).isEqualTo("1500-01-02");
            assertThat(database.queryForObject("SELECT account_id FROM logistics.fms_driver WHERE id=?", java.util.UUID.class, driver)).isEqualTo(driverAccount);
            assertThat(json.readTree(database.queryForObject("SELECT snapshot::text FROM logistics.order_acceptance_event WHERE order_id=?", String.class,
                    java.util.UUID.fromString(accepted.get("id").asString())))).isEqualTo(accepted);
            assertThat(database.queryForObject("SELECT encode(remark,'hex') FROM logistics.tom_order WHERE order_id=?", String.class,
                    java.util.UUID.fromString(accepted.get("id").asString()))).isEqualTo(java.util.HexFormat.of().formatHex(accepted.get("remark").asString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            // Migration preservation is established above before login or new administrative HTTP writes.
            String oldVehicle = tableDigest(database, "fms_vehicle", "id", " WHERE id='" + vehicle + "'");
            String oldDriver = tableDigest(database, "fms_driver", "id", " WHERE id='" + driver + "'");
            verifiesMigratedTaskThroughHttps(database, url, accepted, actor, adminPassword, tableOrders, before);
            assertThat(tableDigest(database, "fms_vehicle", "id", " WHERE id='" + vehicle + "'")).isEqualTo(oldVehicle);
            assertThat(tableDigest(database, "fms_driver", "id", " WHERE id='" + driver + "'")).isEqualTo(oldDriver);
            assertThat(database.queryForObject("SELECT administrative_status FROM logistics.fms_vehicle WHERE id=?", String.class, vehicle)).isEqualTo("INACTIVE");
            assertThat(database.queryForObject("SELECT employment_status FROM logistics.fms_driver WHERE id=?", String.class, driver)).isEqualTo("LEFT");
            assertThat(database.queryForObject("SELECT account_id FROM logistics.fms_driver WHERE id=?", java.util.UUID.class, driver)).isEqualTo(driverAccount);
        } finally {
            // The short TLS context and its pool are closed before the bounded database cleanup.
            String cleanupUrl = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                    + "connectTimeout=5&socketTimeout=10";
            try (var connection = java.sql.DriverManager.getConnection(cleanupUrl, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
            }
        }
    }

    @Test
    void upgradesV9ToV10PreservingEveryExistingRowIncludingActiveDispatchHistoryWithoutExecutionBackfill() throws Exception {
        String databaseName = "xk_execution_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("9").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(9);
            var database = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    url, postgres.getUsername(), postgres.getPassword()));
            JsonNode originalDispatch = seedV9UpgradeDispatch(database, url);
            var tableOrders = v9DomainTableOrders();
            assertThat(tableOrders).hasSize(26);
            var oldTables = new java.util.ArrayList<>(tableOrders.keySet());
            oldTables.add("flyway_schema_history");
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(oldTables);
            var before = new java.util.LinkedHashMap<String, String>();
            tableOrders.forEach((table, ordering) -> {
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                        .as("The V9 preservation fixture populates the existing table %s", table).isPositive();
                before.put(table, tableDigest(database, table, ordering));
            });
            int historyPrefix = database.queryForObject("SELECT max(installed_rank) FROM logistics.flyway_schema_history", Integer.class);
            String historyPredicate = " WHERE installed_rank<=" + historyPrefix;
            String oldHistory = tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate);
            int oldHistoryRows = database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_assignment WHERE status='ASSIGNED'", Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL", Long.class)).isEqualTo(2);
            assertThat(database.queryForObject("SELECT generation FROM logistics.tms_task_dispatch WHERE task_id=?", Long.class,
                    java.util.UUID.fromString(originalDispatch.get("taskId").asString()))).isEqualTo(1);

            Flyway upgrade = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("10").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table, ordering) -> assertThat(tableDigest(database, table, ordering))
                    .as("V10 preserves every existing V9 row in %s", table).isEqualTo(before.get(table)));
            assertThat(tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate)).isEqualTo(oldHistory);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class)).isEqualTo(oldHistoryRows + 1);
            assertThat(database.queryForList("SELECT version FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
            var expectedTables = new java.util.ArrayList<>(oldTables);
            expectedTables.addAll(java.util.List.of("tms_execution", "tms_execution_event", "tms_execution_command"));
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(expectedTables).hasSize(30);
            assertNoExecutionRows(database);
            assertThat(json.readTree(database.queryForObject("SELECT snapshot::text FROM logistics.tms_dispatch_event", String.class)))
                    .isEqualTo(originalDispatch);
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_idempotency", String.class)))
                    .isEqualTo(originalDispatch);
            assertThat(json.readTree(database.queryForObject("SELECT source_snapshot::text FROM logistics.tms_assignment", String.class)))
                    .isEqualTo(originalDispatch.get("assignment").get("source"));
        } finally {
            String cleanupUrl = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                    + "connectTimeout=5&socketTimeout=10";
            try (var connection = java.sql.DriverManager.getConnection(cleanupUrl, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
            }
        }
    }

    @Test
    void upgradesV10ToV11PreservingAllPriorRowsAndDepartureReceiptsWithoutInventingEvidence() throws Exception {
        String databaseName = "xk_evidence_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("10").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(10);
            var database = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    url, postgres.getUsername(), postgres.getPassword()));
            JsonNode originalDispatch = seedV9UpgradeDispatch(database, url);
            JsonNode originalDeparture = seedV10UpgradeDeparture(database, originalDispatch);
            var tableOrders = v10DomainTableOrders();
            assertThat(tableOrders).hasSize(29);
            var oldTables = new java.util.ArrayList<>(tableOrders.keySet());
            oldTables.add("flyway_schema_history");
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(oldTables);
            var before = new java.util.LinkedHashMap<String, String>();
            tableOrders.forEach((table, ordering) -> {
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                        .as("The V10 preservation fixture populates the existing table %s", table).isPositive();
                before.put(table, tableDigest(database, table, ordering));
            });
            int historyPrefix = database.queryForObject("SELECT max(installed_rank) FROM logistics.flyway_schema_history", Integer.class);
            String historyPredicate = " WHERE installed_rank<=" + historyPrefix;
            String oldHistory = tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate);
            int oldHistoryRows = database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_execution WHERE phase='DEPARTED' AND version=1", Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL", Long.class)).isEqualTo(2);

            Flyway upgrade = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("11").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table, ordering) -> assertThat(tableDigest(database, table, ordering))
                    .as("V11 preserves every existing V10 row in %s", table).isEqualTo(before.get(table)));
            assertThat(tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate)).isEqualTo(oldHistory);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class)).isEqualTo(oldHistoryRows + 1);
            assertThat(database.queryForList("SELECT version FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
            var expectedTables = new java.util.ArrayList<>(oldTables);
            expectedTables.addAll(java.util.List.of("tms_execution_evidence", "tms_execution_evidence_object",
                    "tms_execution_event_evidence", "tms_execution_evidence_command"));
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(expectedTables).hasSize(34);
            assertNoEvidenceRows(database);
            assertThat(json.readTree(database.queryForObject("SELECT snapshot::text FROM logistics.tms_execution_event", String.class))).isEqualTo(originalDeparture);
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_execution_command", String.class))).isEqualTo(originalDeparture);
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_idempotency", String.class))).isEqualTo(originalDispatch);
        } finally {
            String cleanupUrl = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                    + "connectTimeout=5&socketTimeout=10";
            try (var connection = java.sql.DriverManager.getConnection(cleanupUrl, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
            }
        }
    }

    @Test
    void upgradesV11ToV12PreservingEveryPriorRowAndVerifiedPrivateEvidenceWithoutInventingExceptions() throws Exception {
        String databaseName = "xk_exception_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("11").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(11);
            var database = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    url, postgres.getUsername(), postgres.getPassword()));
            JsonNode dispatch = seedV9UpgradeDispatch(database, url);
            JsonNode departure = seedV10UpgradeDeparture(database, dispatch);
            EvidenceUpgradeFixture original = seedV11UpgradeEvidence(url, dispatch, departure);
            var tableOrders = v11DomainTableOrders();
            assertThat(tableOrders).hasSize(33);
            var oldTables = new java.util.ArrayList<>(tableOrders.keySet());
            oldTables.add("flyway_schema_history");
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(oldTables);
            var before = new java.util.LinkedHashMap<String, String>();
            tableOrders.forEach((table, ordering) -> {
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                        .as("The V11 preservation fixture populates the existing table %s", table).isPositive();
                before.put(table, tableDigest(database, table, ordering));
            });
            int historyPrefix = database.queryForObject("SELECT max(installed_rank) FROM logistics.flyway_schema_history", Integer.class);
            String historyPredicate = " WHERE installed_rank<=" + historyPrefix;
            String oldHistory = tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate);
            int oldHistoryRows = database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_execution WHERE phase='IN_PROGRESS' AND version=2", Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_execution_event", Long.class)).isEqualTo(2);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_execution_command", Long.class)).isEqualTo(2);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL", Long.class)).isEqualTo(2);

            Flyway upgrade = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("12").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table, ordering) -> assertThat(tableDigest(database, table, ordering))
                    .as("V12 preserves every existing V11 row in %s", table).isEqualTo(before.get(table)));
            assertThat(tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate)).isEqualTo(oldHistory);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class)).isEqualTo(oldHistoryRows + 1);
            assertThat(database.queryForList("SELECT version FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
            var expectedTables = new java.util.ArrayList<>(oldTables);
            expectedTables.addAll(java.util.List.of("tms_exception", "tms_exception_event", "tms_exception_command",
                    "tms_exception_supplement_request", "tms_exception_supplement_evidence", "tms_exception_event_evidence"));
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(expectedTables).hasSize(40);
            assertNoExceptionRows(database);
            assertThat(database.queryForObject("SELECT content FROM logistics.tms_execution_evidence_object", byte[].class)).isEqualTo(original.content());
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_execution_evidence_command", String.class)))
                    .isEqualTo(original.evidence());
            assertThat(json.readTree(database.queryForObject("SELECT snapshot::text FROM logistics.tms_execution_event WHERE to_version=2", String.class)))
                    .isEqualTo(original.progress());
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_execution_command WHERE canonical_path LIKE '%/progress'", String.class)))
                    .isEqualTo(original.progress());
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_execution_command WHERE canonical_path LIKE '%/depart'", String.class)))
                    .isEqualTo(departure);
        } finally {
            String cleanupUrl = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                    + "connectTimeout=5&socketTimeout=10";
            try (var connection = java.sql.DriverManager.getConnection(cleanupUrl, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
            }
        }
    }

    @Test
    void upgradesV12ToV13PreservingAllOriginalColumnsAndPrivateHistoryWithoutInventingCompletions() throws Exception {
        String databaseName = "xk_completion_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("12").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(12);
            var database = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    url, postgres.getUsername(), postgres.getPassword()));
            JsonNode dispatch = seedV9UpgradeDispatch(database, url);
            JsonNode departure = seedV10UpgradeDeparture(database, dispatch);
            EvidenceUpgradeFixture progress = seedV11UpgradeEvidence(url, dispatch, departure);
            ExceptionUpgradeFixture supplement = seedV12UpgradeSupplement(url, dispatch, departure, progress);
            var tableOrders = v12DomainTableOrders();
            assertThat(tableOrders).hasSize(39);
            var oldTables = new java.util.ArrayList<>(tableOrders.keySet());
            oldTables.add("flyway_schema_history");
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(oldTables);
            var before = new java.util.LinkedHashMap<String, String>();
            tableOrders.forEach((table, ordering) -> {
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                        .as("The V12 preservation fixture populates the existing table %s", table).isPositive();
                before.put(table, preservedDomainDigest(database, table, ordering));
            });
            int historyPrefix = database.queryForObject("SELECT max(installed_rank) FROM logistics.flyway_schema_history", Integer.class);
            String historyPredicate = " WHERE installed_rank<=" + historyPrefix;
            String oldHistory = tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate);
            int oldHistoryRows = database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_execution WHERE phase='IN_PROGRESS' AND version=2", Long.class)).isEqualTo(1);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL", Long.class)).isEqualTo(2);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_exception_event", Long.class)).isEqualTo(4);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_exception_command", Long.class)).isEqualTo(4);

            Flyway upgrade = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("13").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table, ordering) -> assertThat(preservedDomainDigest(database, table, ordering))
                    .as("V13 preserves every original V12 column and row in %s", table).isEqualTo(before.get(table)));
            assertThat(tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate)).isEqualTo(oldHistory);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class)).isEqualTo(oldHistoryRows + 1);
            assertThat(database.queryForList("SELECT version FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
            var expectedTables = new java.util.ArrayList<>(oldTables);
            expectedTables.addAll(java.util.List.of("tms_completion", "tms_completion_command"));
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(expectedTables).hasSize(42);
            assertNoCompletionFacts(database);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL AND completion_id IS NULL", Long.class))
                    .as("Every original live occupation remains effective after the metadata-only upgrade").isEqualTo(2);
            for (JsonNode receipt : java.util.List.of(departure, progress.progress())) {
                String suffix = receipt.equals(departure) ? "depart" : "progress";
                assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_execution_command WHERE canonical_path LIKE ?", String.class, "%/" + suffix)))
                        .isEqualTo(receipt);
            }
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_exception_command WHERE canonical_path LIKE '%/submit'", String.class)))
                    .isEqualTo(supplement.submitted());
            assertThat(database.queryForObject("SELECT content FROM logistics.tms_execution_evidence_object WHERE id=(SELECT object_id FROM logistics.tms_execution_evidence WHERE id=?)",
                    byte[].class, java.util.UUID.fromString(supplement.evidence().get("id").asString()))).isEqualTo(supplement.content());
            assertThat(database.queryForObject("SELECT content FROM logistics.tms_execution_evidence_object WHERE id=(SELECT object_id FROM logistics.tms_execution_evidence WHERE id=?)",
                    byte[].class, java.util.UUID.fromString(progress.evidence().get("id").asString()))).isEqualTo(progress.content());
        } finally {
            String cleanupUrl = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                    + "connectTimeout=5&socketTimeout=10";
            try (var connection = java.sql.DriverManager.getConnection(cleanupUrl, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
            }
        }
    }

    @Test
    void upgradesV13ToV14PreservingCompletedMarkersPrivateHistoryAndLegacyClosedOrdersWithoutAdministrativeBackfill() throws Exception {
        String databaseName = "xk_closure_upgrade_test";
        jdbc.execute("CREATE DATABASE " + databaseName);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + databaseName);
        try {
            Flyway previous = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("13").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(13);
            var database = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    url, postgres.getUsername(), postgres.getPassword()));
            JsonNode dispatch = seedV9UpgradeDispatch(database, url);
            JsonNode departure = seedV10UpgradeDeparture(database, dispatch);
            EvidenceUpgradeFixture progress = seedV11UpgradeEvidence(url, dispatch, departure);
            ExceptionUpgradeFixture supplement = seedV12UpgradeSupplement(url, dispatch, departure, progress);
            CompletionUpgradeFixture completion = seedV13UpgradeCompletion(url, dispatch, departure, supplement);
            java.util.UUID originalOrder = java.util.UUID.fromString(dispatch.get("assignment").get("orderId").asString());
            java.util.UUID customer = database.queryForObject("SELECT customer_id FROM logistics.tom_order WHERE order_id=?", java.util.UUID.class, originalOrder);
            JsonNode legacyClosed = seedV8UpgradeOrder(database,
                    java.util.UUID.fromString(dispatch.get("assignment").get("assignedBy").asString()), customer,
                    OffsetDateTime.parse(departure.get("execution").get("departedAt").asString()).plusHours(1), "CLOSED", "ACCEPTED", "LEGACY-M405");
            var tableOrders = v13DomainTableOrders();
            assertThat(tableOrders).hasSize(41);
            var oldTables = new java.util.ArrayList<>(tableOrders.keySet());
            oldTables.add("flyway_schema_history");
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(oldTables);
            var before = new java.util.LinkedHashMap<String, String>();
            tableOrders.forEach((table, ordering) -> {
                assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                        .as("The V13 preservation fixture populates the existing table %s", table).isPositive();
                // V13 already owns completion_id: preserve the FULL row, including its non-NULL relation.
                before.put(table, tableDigest(database, table, ordering));
            });
            int historyPrefix = database.queryForObject("SELECT max(installed_rank) FROM logistics.flyway_schema_history", Integer.class);
            String historyPredicate = " WHERE installed_rank<=" + historyPrefix;
            String oldHistory = tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate);
            int oldHistoryRows = database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class);
            var oldChecksums = database.queryForList("SELECT version,checksum FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank");
            java.util.UUID completionId = java.util.UUID.fromString(completion.result().get("completion").get("id").asString());
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE completion_id=? AND released_at IS NULL AND release_reason IS NULL", Long.class, completionId)).isEqualTo(2);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL AND completion_id IS NULL", Long.class)).isZero();
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_exception WHERE status<>'RESOLVED'", Long.class)).isZero();

            Flyway upgrade = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .schemas("logistics").defaultSchema("logistics").target("14").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            tableOrders.forEach((table, ordering) -> assertThat(tableDigest(database, table, ordering))
                    .as("V14 preserves every complete V13 row, including completed markers, in %s", table).isEqualTo(before.get(table)));
            assertThat(tableDigest(database, "flyway_schema_history", "installed_rank", historyPredicate)).isEqualTo(oldHistory);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.flyway_schema_history", Integer.class)).isEqualTo(oldHistoryRows + 1);
            assertThat(database.queryForList("SELECT version,checksum FROM logistics.flyway_schema_history WHERE installed_rank<=? AND success AND version IS NOT NULL ORDER BY installed_rank", historyPrefix))
                    .isEqualTo(oldChecksums);
            assertThat(database.queryForList("SELECT version FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
            var expectedTables = new java.util.ArrayList<>(oldTables);
            expectedTables.addAll(java.util.List.of("tms_completion_confirmation", "tms_completion_confirmation_command",
                    "tom_order_closure", "tom_order_closure_task", "tom_order_closure_command"));
            assertThat(database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='logistics'", String.class))
                    .containsExactlyInAnyOrderElementsOf(expectedTables).hasSize(47);
            assertNoManagementClosureFacts(database);
            database.queryForList("SELECT logistics.validate_tms_completion(?)", completionId);
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_completion_command", String.class))).isEqualTo(completion.result());
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_execution_command WHERE canonical_path LIKE '%/progress' AND original_data->'execution'->>'version'='3'", String.class)))
                    .isEqualTo(completion.readiness());
            assertThat(json.readTree(database.queryForObject("SELECT original_data::text FROM logistics.tms_exception_command WHERE canonical_path LIKE '%/resolve'", String.class)))
                    .isEqualTo(completion.resolved());
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE completion_id=? AND released_at IS NULL AND release_reason IS NULL", Long.class, completionId)).isEqualTo(2);
            assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL AND completion_id IS NULL", Long.class)).isZero();
            assertThat(database.queryForObject("SELECT lifecycle_status FROM logistics.order_core WHERE id=?", String.class, java.util.UUID.fromString(legacyClosed.get("id").asString()))).isEqualTo("CLOSED");
            assertThat(database.queryForObject("SELECT version FROM logistics.tom_order WHERE order_id=?", Long.class, java.util.UUID.fromString(legacyClosed.get("id").asString())))
                    .isEqualTo(legacyClosed.get("version").asLong());
            for (var photo : java.util.List.of(java.util.Map.entry(progress.evidence(), progress.content()), java.util.Map.entry(supplement.evidence(), supplement.content())))
                assertThat(database.queryForObject("SELECT content FROM logistics.tms_execution_evidence_object WHERE id=(SELECT object_id FROM logistics.tms_execution_evidence WHERE id=?)", byte[].class,
                        java.util.UUID.fromString(photo.getKey().get("id").asString()))).isEqualTo(photo.getValue());
        } finally {
            String cleanupUrl = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                    + "connectTimeout=5&socketTimeout=10";
            try (var connection = java.sql.DriverManager.getConnection(cleanupUrl, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
            }
        }
    }

    /** Build and validate the real V13 reciprocal witness/receipt and exact marked reservation set, without booting V14. */
    private CompletionUpgradeFixture seedV13UpgradeCompletion(String url, JsonNode dispatch, JsonNode departure,
            ExceptionUpgradeFixture supplement) throws Exception {
        JsonNode savedAssignment = dispatch.get("assignment");
        java.util.UUID assignment = java.util.UUID.fromString(departure.get("assignmentId").asString());
        java.util.UUID task = java.util.UUID.fromString(departure.get("taskId").asString());
        java.util.UUID order = java.util.UUID.fromString(savedAssignment.get("orderId").asString());
        java.util.UUID driver = java.util.UUID.fromString(savedAssignment.get("driver").get("id").asString());
        java.util.UUID main = java.util.UUID.fromString(savedAssignment.get("mainVehicle").get("id").asString());
        java.util.UUID manager = java.util.UUID.fromString(savedAssignment.get("assignedBy").asString());
        java.util.UUID exception = java.util.UUID.fromString(supplement.submitted().get("id").asString());
        java.util.UUID evidence = java.util.UUID.fromString(supplement.evidence().get("id").asString());
        var departedAt = java.time.Instant.parse(departure.get("execution").get("departedAt").asString());
        var resolvedAt = java.time.Instant.parse(supplement.evidence().get("uploadedAt").asString()).plusSeconds(120);
        var readinessAt = resolvedAt.plusSeconds(60);
        var completedAt = readinessAt.plusSeconds(60);
        String reason = " 合成\u0000升級管理處置 e\u0301 ";
        var resolvedNode = (tools.jackson.databind.node.ObjectNode) supplement.submitted().deepCopy();
        resolvedNode.put("status", "RESOLVED").put("version", 4).put("blocking", false).put("resolvedAt", resolvedAt.toString()).put("resolutionNote", reason);
        var resolved = json.readValue(json.writeValueAsString(resolvedNode), com.xksystem.logistics.tms.exceptions.ExceptionDtos.Record.class);
        var resolution = com.xksystem.logistics.tms.exceptions.ExceptionValidation.management(json.writeValueAsString(
                json.createObjectNode().put("expectedVersion", 3).put("reason", reason)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var readiness = new com.xksystem.logistics.tms.evidence.EvidenceDtos.ProgressResult(assignment, task,
                new com.xksystem.logistics.tms.execution.ExecutionDtos.View(com.xksystem.logistics.tms.execution.ExecutionPhase.AWAITING_COMPLETION, 3, departedAt));
        var readinessBody = json.createObjectNode().put("expectedVersion", 2).put("targetPhase", "AWAITING_COMPLETION");
        readinessBody.putArray("evidenceIds").add(evidence.toString());
        var readinessInput = com.xksystem.logistics.tms.evidence.EvidenceValidation.progress(json.writeValueAsString(readinessBody).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.UUID completion = java.util.UUID.randomUUID(), completionEvent = java.util.UUID.randomUUID(), receipt = java.util.UUID.randomUUID();
        java.util.UUID readinessEvent = java.util.UUID.randomUUID(), readinessReceipt = java.util.UUID.randomUUID();
        var result = new com.xksystem.logistics.tms.completion.CompletionDtos.Result(assignment, task,
                new com.xksystem.logistics.tms.execution.ExecutionDtos.View(com.xksystem.logistics.tms.execution.ExecutionPhase.COMPLETED, 4, departedAt),
                new com.xksystem.logistics.tms.completion.CompletionDtos.Witness(completion, completedAt, completedAt, com.xksystem.logistics.tms.PolicyMode.SYNTHETIC_TEST_ONLY, true));
        var completeBody = json.createObjectNode().put("expectedVersion", 3).put("confirmSafeRelease", true);
        completeBody.putArray("evidenceIds").add(evidence.toString());
        var input = com.xksystem.logistics.tms.completion.CompletionValidation.command(json.writeValueAsString(completeBody).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var rule = new com.xksystem.logistics.tms.completion.CompletionPolicy.Requirements("LATEST_AWAITING_TRANSITION", 1, java.util.List.of("image/png"), 65536, 4096, true);
        String snapshot = json.writeValueAsString(result), readinessSnapshot = json.writeValueAsString(readiness);
        try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            var transaction = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            transaction.setQueryTimeout(10);
            try {
                transaction.queryForList("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE");
                java.util.UUID actor = transaction.queryForObject("SELECT account_id FROM logistics.fms_driver WHERE id=? FOR UPDATE", java.util.UUID.class, driver);
                assertThat(transaction.queryForObject("SELECT role FROM logistics.upms_account WHERE id=? AND enabled", String.class, actor)).isEqualTo("DRIVER");
                assertThat(transaction.queryForObject("SELECT role FROM logistics.upms_account WHERE id=? AND enabled", String.class, manager)).isEqualTo("ADMIN");
                transaction.queryForList("SELECT id FROM logistics.fms_vehicle WHERE id=? FOR UPDATE", main);
                transaction.queryForList("SELECT id FROM logistics.order_core WHERE id=? FOR UPDATE", order);
                transaction.queryForList("SELECT order_id FROM logistics.tom_order WHERE order_id=? FOR UPDATE", order);
                transaction.queryForList("SELECT task_id FROM logistics.tms_task_dispatch WHERE task_id=? FOR UPDATE", task);
                transaction.queryForList("SELECT id FROM logistics.tms_assignment WHERE id=? FOR UPDATE", assignment);
                var reservations = transaction.queryForList("SELECT id,position,resource_kind,resource_id FROM logistics.tms_resource_reservation WHERE assignment_id=? ORDER BY position,id FOR UPDATE", assignment);
                transaction.queryForList("SELECT assignment_id FROM logistics.tms_execution WHERE assignment_id=? FOR UPDATE", assignment);
                assertThat(transaction.update("UPDATE logistics.tms_exception SET status='RESOLVED',version=4,resolved_at=?,resolution_note=? WHERE id=? AND status='OPEN' AND version=3 AND active_request_id IS NULL",
                        resolvedAt.atOffset(ZoneOffset.UTC), reason.getBytes(java.nio.charset.StandardCharsets.UTF_8), exception)).isEqualTo(1);
                var before = json.readValue(json.writeValueAsString(supplement.submitted()), com.xksystem.logistics.tms.exceptions.ExceptionDtos.Record.class);
                upgradeExceptionEvent(transaction, resolved, before, "RESOLVED", reason, null, manager, resolvedAt, resolved);
                upgradeExceptionReceipt(transaction, resolved, driver, manager, null, "/api/v1/tms/exceptions/" + exception + "/resolve", resolution.hash(), resolved, false, resolvedAt);
                assertThat(transaction.queryForObject("SELECT count(*) FROM logistics.tms_exception WHERE assignment_id=? AND status<>'RESOLVED'", Long.class, assignment)).isZero();
                assertThat(transaction.update("UPDATE logistics.tms_execution SET phase='AWAITING_COMPLETION',version=3,updated_at=? WHERE assignment_id=? AND phase='IN_PROGRESS' AND version=2",
                        readinessAt.atOffset(ZoneOffset.UTC), assignment)).isEqualTo(1);
                transaction.update("INSERT INTO logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version,actor_id,occurred_at,snapshot) VALUES (?,?,'IN_PROGRESS','AWAITING_COMPLETION',2,3,?,?,?::json)",
                        readinessEvent, assignment, actor, readinessAt.atOffset(ZoneOffset.UTC), readinessSnapshot);
                transaction.update("INSERT INTO logistics.tms_execution_event_evidence(id,event_id,evidence_id,assignment_id,input_position,from_phase,from_version) VALUES (?,?,?,?,0,'IN_PROGRESS',2)",
                        java.util.UUID.randomUUID(), readinessEvent, evidence, assignment);
                transaction.update("INSERT INTO logistics.tms_execution_command(id,company_scope,actor_id,driver_id,http_method,canonical_path,idempotency_key,request_hash,assignment_id,task_id,original_status,original_data,created_at) VALUES (?,'SINGLE_COMPANY',?,?,'POST',?,?,?,?,?,200,?::json,?)",
                        readinessReceipt, actor, driver, "/api/v1/tms/my-tasks/" + assignment + "/progress", "synthetic-v13-readiness-" + readinessEvent,
                        readinessInput.hash(), assignment, task, readinessSnapshot, readinessAt.atOffset(ZoneOffset.UTC));
                assertThat(transaction.queryForObject("SELECT logistics.tms_progress_success(?)", java.util.UUID.class, readinessEvent)).isEqualTo(readinessReceipt);
                var at = completedAt.atOffset(ZoneOffset.UTC);
                assertThat(transaction.update("UPDATE logistics.tms_execution SET phase='COMPLETED',version=4,updated_at=? WHERE assignment_id=? AND phase='AWAITING_COMPLETION' AND version=3", at, assignment)).isEqualTo(1);
                transaction.update("INSERT INTO logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version,actor_id,occurred_at,snapshot) VALUES (?,?,'AWAITING_COMPLETION','COMPLETED',3,4,?,?,?::json)",
                        completionEvent, assignment, actor, at, snapshot);
                String columns = "id,assignment_id,task_id,order_id,driver_id,main_vehicle_id,trailer_id,source_order_version,from_execution_version,to_execution_version,terminal_phase,source_event_id,source_command_id,source_from_phase,source_to_phase,source_from_version,completion_event_id,completion_from_phase,completion_to_phase,evidence_ids,actor_id,completed_at,released_at,safe_release_confirmed,policy_mode,policy_snapshot,receipt_id";
                transaction.update("INSERT INTO logistics.tms_completion(" + columns + ") VALUES (?,?,?,?,?,?,NULL,1,3,4,'COMPLETED',?,?,'IN_PROGRESS','AWAITING_COMPLETION',2,?,'AWAITING_COMPLETION','COMPLETED',?::uuid[],?,?,?,true,'SYNTHETIC_TEST_ONLY',?::json,?)",
                        completion, assignment, task, order, driver, main, readinessEvent, readinessReceipt, completionEvent,
                        "{" + evidence + "}", actor, at, at, json.writeValueAsString(rule), receipt);
                assertThat(reservations).hasSize(2);
                int marked = 0;
                for (var reservation : reservations) marked += transaction.update("UPDATE logistics.tms_resource_reservation SET completion_id=? WHERE id=? AND assignment_id=? AND position=? AND resource_kind=? AND resource_id=? AND released_at IS NULL AND completion_id IS NULL",
                        completion, reservation.get("id"), assignment, reservation.get("position"), reservation.get("resource_kind"), reservation.get("resource_id"));
                assertThat(marked).isEqualTo(2);
                transaction.update("INSERT INTO logistics.tms_completion_command(id,completion_id,company_scope,actor_id,driver_id,assignment_id,task_id,http_method,canonical_path,idempotency_key,request_hash,original_status,original_data,created_at) VALUES (?,?,'SINGLE_COMPANY',?,?,?,?,'POST',?,?,?,200,?::json,?)",
                        receipt, completion, actor, driver, assignment, task, "/api/v1/tms/my-tasks/" + assignment + "/complete", "synthetic-v13-complete-" + completion, input.hash(), snapshot, at);
                transaction.execute("SET CONSTRAINTS ALL IMMEDIATE");
                transaction.queryForList("SELECT logistics.validate_tms_completion(?)", completion);
                connection.commit();
            } catch (Exception | Error failure) {
                connection.rollback();
                throw failure;
            }
        }
        return new CompletionUpgradeFixture(json.readTree(snapshot), json.readTree(readinessSnapshot), json.readTree(json.writeValueAsString(resolved)));
    }

    private record CompletionUpgradeFixture(JsonNode result, JsonNode readiness, JsonNode resolved) {}

    /** Frozen V12 report/request/upload/submission facts; no latest application, terminal state or release is fabricated. */
    private ExceptionUpgradeFixture seedV12UpgradeSupplement(String url, JsonNode dispatch, JsonNode departure,
            EvidenceUpgradeFixture progress) throws Exception {
        JsonNode assignmentSource = dispatch.get("assignment");
        java.util.UUID assignment = java.util.UUID.fromString(departure.get("assignmentId").asString());
        java.util.UUID task = java.util.UUID.fromString(departure.get("taskId").asString());
        java.util.UUID driver = java.util.UUID.fromString(assignmentSource.get("driver").get("id").asString());
        java.util.UUID manager = java.util.UUID.fromString(assignmentSource.get("assignedBy").asString());
        java.util.UUID exception = java.util.UUID.randomUUID(), request = java.util.UUID.randomUUID();
        java.util.UUID evidenceId = java.util.UUID.randomUUID(), objectId = java.util.UUID.randomUUID();
        String description = " 合成\u0000升級回報 e\u0301\n ", reason = " 合成\u0000補件要求 e\u0301 ", note = " 合成\u0000補件說明 e\u0301 ";
        var reportedAt = java.time.Instant.parse(departure.get("execution").get("departedAt").asString()).plusSeconds(180);
        var requestedAt = reportedAt.plusSeconds(60);
        var uploadedAt = requestedAt.plusSeconds(60);
        var submittedAt = uploadedAt.plusSeconds(60);
        byte[] content = progress.content().clone(); // A new immutable artifact, never relabel the original M402 photograph.
        var evidence = new com.xksystem.logistics.tms.evidence.EvidenceDtos.Evidence(evidenceId, assignment, task,
                com.xksystem.logistics.tms.execution.ExecutionPhase.IN_PROGRESS, 2, "image/png", content.length,
                com.xksystem.logistics.tms.evidence.EvidenceValidation.digest(content), uploadedAt, "VERIFIED");
        java.util.function.BiFunction<Long, com.xksystem.logistics.tms.exceptions.ExceptionDtos.ActiveRequest,
                com.xksystem.logistics.tms.exceptions.ExceptionDtos.Record> state = (version, active) ->
                new com.xksystem.logistics.tms.exceptions.ExceptionDtos.Record(exception, assignment, task,
                        assignmentSource.get("source").get("taskNo").asString(), com.xksystem.logistics.tms.exceptions.ReportedPhase.IN_PROGRESS, 2,
                        description, active == null ? com.xksystem.logistics.tms.exceptions.ExceptionStatus.OPEN : com.xksystem.logistics.tms.exceptions.ExceptionStatus.NEEDS_SUPPLEMENT,
                        version, true, reportedAt, null, null, active);
        var reported = state.apply(0L, null);
        var requested = state.apply(1L, new com.xksystem.logistics.tms.exceptions.ExceptionDtos.ActiveRequest(request, reason, requestedAt, 0, java.util.List.of()));
        var uploaded = state.apply(2L, new com.xksystem.logistics.tms.exceptions.ExceptionDtos.ActiveRequest(request, reason, requestedAt, 1, java.util.List.of(evidenceId)));
        var submitted = state.apply(3L, null);
        var uploadResult = new com.xksystem.logistics.tms.exceptions.ExceptionDtos.UploadResult(exception, assignment, task, 2, request, evidence);
        var rule = new com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy.Requirements(1, java.util.List.of("image/png"), 65536, 4096);
        String selfPath = "/api/v1/tms/my-tasks/" + assignment + "/exceptions";
        String uploadPath = selfPath + "/" + exception + "/supplement-requests/" + request + "/evidence";
        var reportInput = com.xksystem.logistics.tms.exceptions.ExceptionValidation.report(json.writeValueAsString(
                json.createObjectNode().put("expectedExecutionVersion", 2).put("description", description)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var requestInput = com.xksystem.logistics.tms.exceptions.ExceptionValidation.management(json.writeValueAsString(
                json.createObjectNode().put("expectedVersion", 0).put("reason", reason)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var submitBody = json.createObjectNode().put("expectedVersion", 2).put("note", note);
        submitBody.putArray("evidenceIds").add(evidenceId.toString());
        var submitInput = com.xksystem.logistics.tms.exceptions.ExceptionValidation.submit(json.writeValueAsString(submitBody).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var uploadBody = json.createObjectNode().put("path", uploadPath).put("method", "POST").put("expectedVersion", 1)
                .put("expectedExecutionVersion", 2).put("mediaType", "image/png").put("byteSize", content.length).put("sha256", evidence.sha256());
        try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            var transaction = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            transaction.setQueryTimeout(10);
            try {
                java.util.UUID actor = transaction.queryForObject("SELECT account_id FROM logistics.fms_driver WHERE id=?", java.util.UUID.class, driver);
                assertThat(transaction.queryForObject("SELECT role FROM logistics.upms_account WHERE id=? AND enabled", String.class, actor)).isEqualTo("DRIVER");
                assertThat(transaction.queryForObject("SELECT role FROM logistics.upms_account WHERE id=? AND enabled", String.class, manager)).isEqualTo("ADMIN");
                transaction.update("INSERT INTO logistics.tms_exception(id,assignment_id,task_id,driver_id,task_no,reported_phase,reported_execution_version,description,source_snapshot,driver_snapshot,policy_mode,actor_id,reported_at,status,version) VALUES (?,?,?,?,?,'IN_PROGRESS',2,?,?::json,?::json,'SYNTHETIC_TEST_ONLY',?,?,'OPEN',0)",
                        exception, assignment, task, driver, reported.taskNo(), description.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        json.writeValueAsString(assignmentSource.get("source")), json.writeValueAsString(assignmentSource.get("driver")), actor, reportedAt.atOffset(ZoneOffset.UTC));
                upgradeExceptionEvent(transaction, reported, null, "REPORTED", description, null, actor, reportedAt, reported);
                upgradeExceptionReceipt(transaction, reported, driver, actor, null, selfPath, reportInput.hash(), reported, false, reportedAt);
                transaction.update("INSERT INTO logistics.tms_exception_supplement_request(id,exception_id,assignment_id,requested_version,reason,actor_id,requested_at,policy_snapshot) VALUES (?,?,?,1,?,?,?,?::json)",
                        request, exception, assignment, reason.getBytes(java.nio.charset.StandardCharsets.UTF_8), manager, requestedAt.atOffset(ZoneOffset.UTC), json.writeValueAsString(rule));
                assertThat(transaction.update("UPDATE logistics.tms_exception SET status='NEEDS_SUPPLEMENT',version=1,active_request_id=? WHERE id=? AND version=0", request, exception)).isEqualTo(1);
                upgradeExceptionEvent(transaction, requested, reported, "SUPPLEMENT_REQUESTED", reason, request, manager, requestedAt, requested);
                upgradeExceptionReceipt(transaction, requested, driver, manager, request, "/api/v1/tms/exceptions/" + exception + "/supplement-requests", requestInput.hash(), requested, false, requestedAt);
                transaction.update("INSERT INTO logistics.tms_execution_evidence_object(id,content,byte_size,sha256) VALUES (?,?,?,?)", objectId, content, content.length, evidence.sha256());
                transaction.update("INSERT INTO logistics.tms_execution_evidence(id,assignment_id,task_id,driver_id,actor_id,phase,execution_version,media_type,byte_size,sha256,uploaded_at,status,object_id,policy_snapshot) VALUES (?,?,?,?,?,'IN_PROGRESS',2,'image/png',?,?,?,'VERIFIED',?,?::json)",
                        evidenceId, assignment, task, driver, actor, content.length, evidence.sha256(), uploadedAt.atOffset(ZoneOffset.UTC), objectId, json.writeValueAsString(rule));
                assertThat(transaction.update("UPDATE logistics.tms_exception SET version=2 WHERE id=? AND version=1", exception)).isEqualTo(1);
                java.util.UUID uploadEvent = upgradeExceptionEvent(transaction, uploaded, requested, "SUPPLEMENT_EVIDENCE_UPLOADED", null, request, actor, uploadedAt, uploadResult);
                transaction.update("INSERT INTO logistics.tms_exception_supplement_evidence(id,exception_id,assignment_id,request_id,evidence_id,event_id) VALUES (?,?,?,?,?,?)",
                        java.util.UUID.randomUUID(), exception, assignment, request, evidenceId, uploadEvent);
                upgradeExceptionReceipt(transaction, uploaded, driver, actor, request, uploadPath, com.xksystem.logistics.tms.TmsValidation.hash(uploadBody), uploadResult, true, uploadedAt);
                assertThat(transaction.update("UPDATE logistics.tms_exception SET status='OPEN',version=3,active_request_id=NULL WHERE id=? AND version=2", exception)).isEqualTo(1);
                java.util.UUID submitEvent = upgradeExceptionEvent(transaction, submitted, uploaded, "SUPPLEMENT_SUBMITTED", note, request, actor, submittedAt, submitted);
                transaction.update("INSERT INTO logistics.tms_exception_event_evidence(id,event_id,exception_id,assignment_id,request_id,evidence_id,input_position) VALUES (?,?,?,?,?,?,0)",
                        java.util.UUID.randomUUID(), submitEvent, exception, assignment, request, evidenceId);
                upgradeExceptionReceipt(transaction, submitted, driver, actor, request,
                        selfPath + "/" + exception + "/supplement-requests/" + request + "/submit", submitInput.hash(), submitted, false, submittedAt);
                connection.commit();
            } catch (Exception | Error failure) {
                connection.rollback();
                throw failure;
            }
        }
        return new ExceptionUpgradeFixture(json.readTree(json.writeValueAsString(submitted)), json.readTree(json.writeValueAsString(evidence)), content);
    }

    private java.util.UUID upgradeExceptionEvent(JdbcTemplate database, com.xksystem.logistics.tms.exceptions.ExceptionDtos.Record after,
            com.xksystem.logistics.tms.exceptions.ExceptionDtos.Record before, String type, String note, java.util.UUID request,
            java.util.UUID actor, java.time.Instant at, Object snapshot) {
        java.util.UUID id = java.util.UUID.randomUUID();
        var account = database.queryForMap("SELECT role,display_name FROM logistics.upms_account WHERE id=?", actor);
        database.update("INSERT INTO logistics.tms_exception_event(id,exception_id,assignment_id,type,from_status,to_status,from_version,to_version,request_id,note,actor_id,actor_role,actor_display_name,occurred_at,snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::json)",
                id, after.id(), after.assignmentId(), type, before == null ? null : before.status().name(), after.status().name(),
                before == null ? null : before.version(), after.version(), request, note == null ? null : note.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actor, account.get("role"), account.get("display_name").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), at.atOffset(ZoneOffset.UTC), json.writeValueAsString(snapshot));
        return id;
    }

    private void upgradeExceptionReceipt(JdbcTemplate database, com.xksystem.logistics.tms.exceptions.ExceptionDtos.Record after,
            java.util.UUID driver, java.util.UUID actor, java.util.UUID request, String path, String hash, Object result, boolean upload, java.time.Instant at) {
        database.update("INSERT INTO logistics.tms_exception_command(id,company_scope,actor_id,exception_id,assignment_id,task_id,driver_id,request_id,http_method,canonical_path,idempotency_key,request_hash,original_status,result_type,original_data,created_at) VALUES (?,'SINGLE_COMPANY',?,?,?,?,?,?,'POST',?,?,?,200,?,?::json,?)",
                java.util.UUID.randomUUID(), actor, after.id(), after.assignmentId(), after.taskId(), driver, request, path,
                "synthetic-v12-" + java.util.UUID.randomUUID(), hash, upload ? "UPLOAD" : "RECORD", json.writeValueAsString(result), at.atOffset(ZoneOffset.UTC));
    }

    private record ExceptionUpgradeFixture(JsonNode submitted, JsonNode evidence, byte[] content) {}

    /** Coherent V11 photo/upload/progress facts committed together, before any V12 runtime or migration is loaded. */
    private EvidenceUpgradeFixture seedV11UpgradeEvidence(String url, JsonNode dispatch, JsonNode departure) throws Exception {
        java.util.UUID assignment = java.util.UUID.fromString(departure.get("assignmentId").asString());
        java.util.UUID task = java.util.UUID.fromString(departure.get("taskId").asString());
        java.util.UUID driver = java.util.UUID.fromString(dispatch.get("assignment").get("driver").get("id").asString());
        var departedAt = java.time.Instant.parse(departure.get("execution").get("departedAt").asString());
        var uploadedAt = departedAt.plusSeconds(60);
        var progressedAt = uploadedAt.plusSeconds(60);
        byte[] content;
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xff225588);
        try (var output = new java.io.ByteArrayOutputStream()) {
            assertThat(javax.imageio.ImageIO.write(image, "png", output)).isTrue();
            content = output.toByteArray();
        }
        java.util.UUID evidenceId = java.util.UUID.randomUUID(), objectId = java.util.UUID.randomUUID();
        var evidence = new com.xksystem.logistics.tms.evidence.EvidenceDtos.Evidence(evidenceId, assignment, task,
                com.xksystem.logistics.tms.execution.ExecutionPhase.DEPARTED, 1, "image/png", content.length,
                com.xksystem.logistics.tms.evidence.EvidenceValidation.digest(content), uploadedAt, "VERIFIED");
        var progress = new com.xksystem.logistics.tms.evidence.EvidenceDtos.ProgressResult(assignment, task,
                new com.xksystem.logistics.tms.execution.ExecutionDtos.View(com.xksystem.logistics.tms.execution.ExecutionPhase.IN_PROGRESS, 2, departedAt));
        String evidenceJson = json.writeValueAsString(evidence), progressJson = json.writeValueAsString(progress);
        String uploadPath = "/api/v1/tms/my-tasks/" + assignment + "/evidence";
        var request = json.createObjectNode().put("expectedVersion", 1).put("targetPhase", "IN_PROGRESS");
        request.putArray("evidenceIds").add(evidenceId.toString());
        var command = com.xksystem.logistics.tms.evidence.EvidenceValidation.progress(
                json.writeValueAsString(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // These are the frozen isolated-engineering fixture requirements, never a production policy bean.
        var rule = new com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy.Requirements(1, java.util.List.of("image/png"), 65536, 4096);
        try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            var transaction = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            transaction.setQueryTimeout(10);
            try {
                java.util.UUID actor = transaction.queryForObject("SELECT account_id FROM logistics.fms_driver WHERE id=?", java.util.UUID.class, driver);
                assertThat(transaction.queryForObject("SELECT role FROM logistics.upms_account WHERE id=? AND enabled", String.class, actor)).isEqualTo("DRIVER");
                transaction.update("INSERT INTO logistics.tms_execution_evidence_object(id,content,byte_size,sha256) VALUES (?,?,?,?)",
                        objectId, content, content.length, evidence.sha256());
                transaction.update("INSERT INTO logistics.tms_execution_evidence(id,assignment_id,task_id,driver_id,actor_id,phase,execution_version,media_type,byte_size,sha256,uploaded_at,status,object_id,policy_snapshot) VALUES (?,?,?,?,?,'DEPARTED',1,'image/png',?, ?,?,'VERIFIED',?,?::json)",
                        evidenceId, assignment, task, driver, actor, content.length, evidence.sha256(), uploadedAt.atOffset(ZoneOffset.UTC), objectId, json.writeValueAsString(rule));
                transaction.update("INSERT INTO logistics.tms_execution_evidence_command(id,company_scope,actor_id,driver_id,assignment_id,task_id,evidence_id,http_method,canonical_path,idempotency_key,request_hash,original_status,original_data,created_at) VALUES (?,'SINGLE_COMPANY',?,?,?,?,?,'POST',?,?,?,200,?::json,?)",
                        java.util.UUID.randomUUID(), actor, driver, assignment, task, evidenceId, uploadPath,
                        "synthetic-v11-upload-" + evidenceId, com.xksystem.logistics.tms.evidence.EvidenceValidation.uploadHash(uploadPath, 1, "image/png", content),
                        evidenceJson, uploadedAt.atOffset(ZoneOffset.UTC));
                assertThat(transaction.update("UPDATE logistics.tms_execution SET phase='IN_PROGRESS',version=2,updated_at=? WHERE assignment_id=? AND phase='DEPARTED' AND version=1",
                        progressedAt.atOffset(ZoneOffset.UTC), assignment)).isEqualTo(1);
                java.util.UUID event = java.util.UUID.randomUUID();
                transaction.update("INSERT INTO logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version,actor_id,occurred_at,snapshot) VALUES (?,?,'DEPARTED','IN_PROGRESS',1,2,?,?,?::json)",
                        event, assignment, actor, progressedAt.atOffset(ZoneOffset.UTC), progressJson);
                transaction.update("INSERT INTO logistics.tms_execution_event_evidence(id,event_id,evidence_id,assignment_id,input_position,from_phase,from_version) VALUES (?,?,?,?,0,'DEPARTED',1)",
                        java.util.UUID.randomUUID(), event, evidenceId, assignment);
                transaction.update("INSERT INTO logistics.tms_execution_command(id,company_scope,actor_id,driver_id,http_method,canonical_path,idempotency_key,request_hash,assignment_id,task_id,original_status,original_data,created_at) VALUES (?,'SINGLE_COMPANY',?,?,'POST',?,?,?,?,?,200,?::json,?)",
                        java.util.UUID.randomUUID(), actor, driver, "/api/v1/tms/my-tasks/" + assignment + "/progress", "synthetic-v11-progress-" + event,
                        command.hash(), assignment, task, progressJson, progressedAt.atOffset(ZoneOffset.UTC));
                connection.commit(); // Also checks the original private-object reverse deferred FK.
            } catch (Exception | Error failure) {
                connection.rollback();
                throw failure;
            }
        }
        return new EvidenceUpgradeFixture(json.readTree(evidenceJson), json.readTree(progressJson), content);
    }

    private record EvidenceUpgradeFixture(JsonNode evidence, JsonNode progress, byte[] content) {}

    /** Actual V10 departure shape: the bound DRIVER is the actor, independent of the original manager assignment actor. */
    private JsonNode seedV10UpgradeDeparture(JdbcTemplate database, JsonNode originalDispatch) {
        JsonNode assignment = originalDispatch.get("assignment");
        java.util.UUID assignmentId = java.util.UUID.fromString(assignment.get("id").asString());
        java.util.UUID taskId = java.util.UUID.fromString(originalDispatch.get("taskId").asString());
        java.util.UUID driverId = java.util.UUID.fromString(assignment.get("driver").get("id").asString());
        java.util.UUID actor = database.queryForObject("SELECT account_id FROM logistics.fms_driver WHERE id=?", java.util.UUID.class, driverId);
        assertThat(actor).isNotNull();
        assertThat(database.queryForObject("SELECT role FROM logistics.upms_account WHERE id=? AND enabled", String.class, actor)).isEqualTo("DRIVER");
        assertThat(actor.toString()).isNotEqualTo(assignment.get("assignedBy").asString());
        var departedAt = java.time.Instant.parse(assignment.get("assignedAt").asString()).plusSeconds(60);
        var result = new com.xksystem.logistics.tms.execution.ExecutionDtos.DepartureResult(assignmentId, taskId,
                new com.xksystem.logistics.tms.execution.ExecutionDtos.View(com.xksystem.logistics.tms.execution.ExecutionPhase.DEPARTED, 1, departedAt));
        String snapshot = json.writeValueAsString(result);
        var at = departedAt.atOffset(ZoneOffset.UTC);
        var command = com.xksystem.logistics.tms.execution.ExecutionValidation.command(
                json.writeValueAsString(json.createObjectNode().put("expectedVersion", 0)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        database.update("INSERT INTO logistics.tms_execution(assignment_id,phase,version,departed_at,updated_at) VALUES (?,'DEPARTED',1,?,?)",
                assignmentId, at, at);
        database.update("INSERT INTO logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version,actor_id,occurred_at,snapshot) VALUES (?,?,'WAITING','DEPARTED',0,1,?,?,?::json)",
                java.util.UUID.randomUUID(), assignmentId, actor, at, snapshot);
        database.update("INSERT INTO logistics.tms_execution_command(id,company_scope,actor_id,driver_id,http_method,canonical_path,idempotency_key,request_hash,assignment_id,task_id,original_status,original_data,created_at) VALUES (?,'SINGLE_COMPANY',?,?,'POST',?,?,?,?,?,200,?::json,?)",
                java.util.UUID.randomUUID(), actor, driverId, "/api/v1/tms/my-tasks/" + assignmentId + "/depart",
                "synthetic-v10-depart-" + assignmentId, command.hash(), assignmentId, taskId, snapshot, at);
        return json.readTree(snapshot);
    }

    /** Frozen V9 synthetic data, inserted before V10 exists; never starts a latest-version writer to prepare the baseline. */
    private JsonNode seedV9UpgradeDispatch(JdbcTemplate database, String url) throws Exception {
        var at = OffsetDateTime.parse("2026-11-17T08:00:00.123456Z");
        java.util.UUID actor, driverAccount;
        java.util.Map<String, String> definitions;
        try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
            var encoder = new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder();
            actor = com.xksystem.logistics.support.UpmsTestData.insert(connection, encoder,
                    "synthetic-v9-upgrade-admin", "ADMIN", com.xksystem.logistics.support.UpmsTestData.password(), false, at.toInstant());
            driverAccount = com.xksystem.logistics.support.UpmsTestData.insert(connection, encoder,
                    "synthetic-v9-upgrade-driver", "DRIVER", com.xksystem.logistics.support.UpmsTestData.password(), false, at.toInstant());
            definitions = com.xksystem.logistics.support.AdmTestData.insertDefinitions(connection, "EXECUTION_UPGRADE");
        }
        database.update("INSERT INTO logistics.upms_audit(id,actor,target_id,action,reason,occurred_at) VALUES (?,?,?,'LOGIN_SUCCESS','Synthetic V9 upgrade fixture',?)",
                java.util.UUID.randomUUID(), "synthetic-v9-upgrade-admin", actor, at);
        java.util.UUID type = java.util.UUID.randomUUID(), customer = java.util.UUID.randomUUID();
        String typeCode = "SYNTHETIC_TMS_IT_MAIN", typeLabel = "合成升級主車類別";
        database.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) SELECT ?,id,?,?,true,1,false,0,?,? FROM logistics.adm_dict_category WHERE code='VEHICLE_TYPE'",
                type, typeCode, typeLabel, at, at);
        database.update("INSERT INTO logistics.adm_parameter(id,parameter_key,name,value_type,value_text,description,enabled,version,created_at,updated_at) VALUES (?,?,'合成 V9 升級參數','TEXT','upgrade','Synthetic fixture',true,0,?,?)",
                java.util.UUID.randomUUID(), definitions.get("text"), at, at);
        database.update("INSERT INTO logistics.adm_change_log(id,target_type,target_id,action,actor_id,actor_username,occurred_at,before_value,after_value) VALUES (?,'ITEM',?,'CREATE',?,'synthetic-v9-upgrade-admin',?,NULL,?::jsonb)",
                java.util.UUID.randomUUID(), type, actor, at, json.writeValueAsString(json.createObjectNode().put("code", typeCode)));
        database.update("INSERT INTO logistics.crm_customer(id,customer_code,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,'SYNTHETIC_V9_UPGRADE','合成歷史客戶',false,7,?,?,?,?)",
                customer, at, at, actor, actor);
        database.update("INSERT INTO logistics.crm_contact(id,customer_id,name,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成 V9 聯絡人',true,0,?,?,?,?)",
                java.util.UUID.randomUUID(), customer, at, at, actor, actor);
        database.update("INSERT INTO logistics.crm_address(id,customer_id,address,enabled,version,created_at,updated_at,created_by,updated_by) VALUES (?,?,'合成 V9 地址',true,0,?,?,?,?)",
                java.util.UUID.randomUUID(), customer, at, at, actor, actor);
        JsonNode accepted = seedV8UpgradeOrder(database, actor, customer, at, "OPEN", "ACCEPTED", "ACCEPTED");
        java.util.UUID driver = java.util.UUID.randomUUID(), vehicle = java.util.UUID.randomUUID();
        String driverCode = "SYNTHETIC_TMS_IT_D1", driverName = "合成 V9 升級司機", plate = "SYNTHETIC-V9-VEHICLE";
        database.update("INSERT INTO logistics.fms_driver(id,driver_code,name,account_id,employment_status,duty_status,version,created_at,created_by,updated_at,updated_by) VALUES (?,?,?,?,'ACTIVE','ON_DUTY',0,?,?,?,?)",
                driver, driverCode, driverName, driverAccount, at, actor, at, actor);
        database.update("INSERT INTO logistics.fms_vehicle(id,plate_no,vehicle_type_id,capacity_ton,registration_date,inspection_due_date,administrative_status,version,created_at,created_by,updated_at,updated_by) VALUES (?,?,?,12.3400,DATE '1500-01-02',DATE '9999-12-31','AVAILABLE',0,?,?,?,?)",
                vehicle, plate, type, at, actor, at, actor);
        java.util.UUID order = java.util.UUID.fromString(accepted.get("id").asString());
        JsonNode first = accepted.get("tasks").get(0);
        assertThat(first.get("predecessorTaskIds")).isEmpty();
        java.util.UUID task = java.util.UUID.fromString(first.get("id").asString());
        for (JsonNode value : accepted.get("tasks"))
            database.update("INSERT INTO logistics.tms_task_dispatch(task_id,order_id,company_scope,order_no,task_no,source_order_version,generation) VALUES (?,?,'SINGLE_COMPANY',?,?,1,?)",
                    java.util.UUID.fromString(value.get("id").asString()), order, accepted.get("orderNo").asString(), value.get("taskNo").asString(), value.get("id").equals(first.get("id")) ? 1 : 0);
        var assignedAt = at.plusMinutes(1).toInstant();
        java.util.UUID assignmentId = java.util.UUID.randomUUID();
        var driverSnapshot = new com.xksystem.logistics.tms.TmsDtos.DriverSnapshot(driver, driverCode, driverName, 0);
        var mainSnapshot = new com.xksystem.logistics.tms.TmsDtos.VehicleSnapshot(vehicle, plate, type, typeCode, typeLabel, 0);
        var source = json.readValue(json.writeValueAsString(upgradeSource(accepted, first)), com.xksystem.logistics.tom.TaskQueries.Source.class);
        var reservations = java.util.List.of(
                new com.xksystem.logistics.tms.TmsDtos.Reservation(java.util.UUID.randomUUID(), com.xksystem.logistics.tms.ResourceKind.DRIVER,
                        com.xksystem.logistics.tms.ResourcePosition.DRIVER, driver, assignedAt, null, null),
                new com.xksystem.logistics.tms.TmsDtos.Reservation(java.util.UUID.randomUUID(), com.xksystem.logistics.tms.ResourceKind.VEHICLE,
                        com.xksystem.logistics.tms.ResourcePosition.MAIN, vehicle, assignedAt, null, null));
        var event = new com.xksystem.logistics.tms.TmsDtos.Event(java.util.UUID.randomUUID(), "ASSIGNED", assignmentId, null, 0, 1, actor, assignedAt, null);
        var assignment = new com.xksystem.logistics.tms.TmsDtos.Assignment(assignmentId, task, order, "ASSIGNED", 0, 1, null,
                driverSnapshot, mainSnapshot, null, source, com.xksystem.logistics.tms.PolicyMode.SYNTHETIC_TEST_ONLY,
                actor, assignedAt, null, null, null, null, null, reservations, java.util.List.of(event));
        var result = new com.xksystem.logistics.tms.TmsDtos.CommandResult(task, 1, assignment);
        String resultJson = json.writeValueAsString(result);
        var assignedOffset = assignedAt.atOffset(ZoneOffset.UTC);
        database.update("INSERT INTO logistics.tms_assignment(id,task_id,order_id,status,version,assigned_generation,driver_id,main_vehicle_id,driver_snapshot,main_vehicle_snapshot,source_snapshot,policy_mode,assigned_by,assigned_at) VALUES (?,?,?,'ASSIGNED',0,1,?,?,?::json,?::json,?::json,'SYNTHETIC_TEST_ONLY',?,?)",
                assignmentId, task, order, driver, vehicle, json.writeValueAsString(driverSnapshot), json.writeValueAsString(mainSnapshot),
                json.writeValueAsString(source), actor, assignedOffset);
        for (var reservation : reservations) {
            boolean isDriver = reservation.kind() == com.xksystem.logistics.tms.ResourceKind.DRIVER;
            database.update("INSERT INTO logistics.tms_resource_reservation(id,assignment_id,driver_id,vehicle_id,position,acquired_at) VALUES (?,?,?,?,?,?)",
                    reservation.id(), assignmentId, isDriver ? driver : null, isDriver ? null : vehicle, reservation.position().name(), assignedOffset);
        }
        database.update("INSERT INTO logistics.tms_dispatch_event(id,task_id,action,assignment_id,from_generation,to_generation,actor_id,occurred_at,snapshot) VALUES (?,?,'ASSIGNED',?,0,1,?,?,?::json)",
                event.id(), task, assignmentId, actor, assignedOffset, resultJson);
        var command = json.createObjectNode().put("expectedGeneration", 0).put("driverId", driver.toString())
                .put("mainVehicleId", vehicle.toString()).putNull("trailerId");
        com.xksystem.logistics.tms.TmsValidation.command(command, com.xksystem.logistics.tms.TmsValidation.Action.ASSIGN);
        database.update("INSERT INTO logistics.tms_idempotency(id,company_scope,actor_id,http_method,canonical_path,idempotency_key,request_hash,task_id,original_status,original_data,created_at) VALUES (?,'SINGLE_COMPANY',?,'POST',?,?,?,?,200,?::json,?)",
                java.util.UUID.randomUUID(), actor, "/api/v1/tms/tasks/" + task + "/dispatch", "synthetic-v9-assign-" + assignmentId,
                com.xksystem.logistics.tms.TmsValidation.hash(command), task, resultJson, assignedOffset);
        return json.readTree(resultJson);
    }

    private static java.util.Map<String, String> v9DomainTableOrders() {
        var result = new java.util.LinkedHashMap<String, String>();
        for (String table : java.util.List.of("upms_lock", "upms_account", "upms_audit", "adm_dict_category", "adm_dict_item",
                "adm_parameter", "adm_change_log", "crm_customer", "crm_contact", "crm_address", "order_core", "cargo_item",
                "transport_task", "order_draft_revision", "api_idempotency", "fms_vehicle", "fms_driver", "order_acceptance_event",
                "tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency")) result.put(table, "id");
        result.put("adm_parameter_definition", "parameter_key");
        result.put("tom_order", "order_id");
        result.put("task_dependency", "order_id,task_id,predecessor_task_id");
        result.put("tms_task_dispatch", "task_id");
        return result;
    }

    private static void assertNoExecutionRows(JdbcTemplate database) {
        for (String table : java.util.List.of("tms_execution", "tms_execution_event", "tms_execution_command"))
            assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                    .as("No execution state, departure actor/time, event or receipt is invented in %s", table).isZero();
    }

    private static java.util.Map<String, String> v10DomainTableOrders() {
        var result = new java.util.LinkedHashMap<>(v9DomainTableOrders());
        result.put("tms_execution", "assignment_id");
        result.put("tms_execution_event", "id");
        result.put("tms_execution_command", "id");
        return result;
    }

    private static void assertNoEvidenceRows(JdbcTemplate database) {
        for (String table : java.util.List.of("tms_execution_evidence", "tms_execution_evidence_object",
                "tms_execution_event_evidence", "tms_execution_evidence_command"))
            assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                    .as("No evidence metadata, private bytes, event links or upload receipts are invented in %s", table).isZero();
    }

    private static java.util.Map<String, String> v11DomainTableOrders() {
        var result = new java.util.LinkedHashMap<>(v10DomainTableOrders());
        for (String table : java.util.List.of("tms_execution_evidence", "tms_execution_evidence_object",
                "tms_execution_event_evidence", "tms_execution_evidence_command")) result.put(table, "id");
        return result;
    }

    private static void assertNoExceptionRows(JdbcTemplate database) {
        for (String table : java.util.List.of("tms_exception", "tms_exception_event", "tms_exception_command",
                "tms_exception_supplement_request", "tms_exception_supplement_evidence", "tms_exception_event_evidence"))
            assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                    .as("No exception, request, supplement or history is invented in %s", table).isZero();
    }

    private static java.util.Map<String, String> v12DomainTableOrders() {
        var result = new java.util.LinkedHashMap<>(v11DomainTableOrders());
        for (String table : java.util.List.of("tms_exception", "tms_exception_event", "tms_exception_command",
                "tms_exception_supplement_request", "tms_exception_supplement_evidence", "tms_exception_event_evidence")) result.put(table, "id");
        return result;
    }

    private void verifiesMigratedTaskThroughHttps(JdbcTemplate database, String url, JsonNode accepted,
            java.util.UUID actor, String adminPassword, java.util.Map<String, String> tableOrders,
            java.util.Map<String, String> beforeMigration) throws Exception {
        // The caller has completed the historical V8->V9 proof. Current runtime compatibility separately applies V10 through V14.
        assertThat(database.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics'", Long.class)).isEqualTo(27);
        assertThat(database.queryForList("SELECT version FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        var beforeRuntime = new java.util.LinkedHashMap<String, String>();
        v9DomainTableOrders().forEach((table, ordering) -> beforeRuntime.put(table, preservedDomainDigest(database, table, ordering)));
        int historyPrefix = database.queryForObject("SELECT max(installed_rank) FROM logistics.flyway_schema_history", Integer.class);
        String oldHistory = tableDigest(database, "flyway_schema_history", "installed_rank", " WHERE installed_rank<=" + historyPrefix);
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("spring.datasource.url", url);
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("server.port", 0);
        properties.put("server.shutdown", "immediate");
        properties.put("spring.lifecycle.timeout-per-shutdown-phase", "5s");
        com.xksystem.logistics.support.TlsTestSupport.register((name, supplier) -> properties.put(name, supplier.get()));
        var application = com.xksystem.logistics.support.TmsE2eApplication.createApplication();
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("isolated-upgrade-https", properties)));
        try (var runtime = application.run()) {
            assertThat(database.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics'", Long.class)).isEqualTo(47);
            assertThat(database.queryForList("SELECT version FROM logistics.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
            assertThat(runtime.getBean(Flyway.class).validateWithResult().validationSuccessful).isTrue();
            assertNoExecutionRows(database);
            assertNoEvidenceRows(database);
            assertNoExceptionRows(database);
            assertNoCompletionFacts(database);
            assertNoManagementClosureFacts(database);
            v9DomainTableOrders().forEach((table, ordering) -> assertThat(preservedDomainDigest(database, table, ordering))
                    .as("Current runtime's automatic V10-V14 migrations preserve every original V9 column in %s", table).isEqualTo(beforeRuntime.get(table)));
            assertThat(tableDigest(database, "flyway_schema_history", "installed_rank", " WHERE installed_rank<=" + historyPrefix)).isEqualTo(oldHistory);
            assertThat(runtime.getBeansOfType(java.time.Clock.class)).containsOnlyKeys("clock");
            assertThat(runtime.getBeansOfType(com.xksystem.logistics.support.MutableTestClock.class)).isEmpty();
            assertThat(runtime.getBeansWithAnnotation(org.springframework.boot.test.context.TestConfiguration.class)).isEmpty();
            assertThat(runtime.getBean(com.xksystem.logistics.tms.DispatchEligibilityPolicy.class).mode())
                    .isEqualTo(com.xksystem.logistics.tms.PolicyMode.SYNTHETIC_TEST_ONLY);
            assertThat(runtime.getBean(JdbcTemplate.class).queryForObject("SELECT current_database()", String.class))
                    .isEqualTo("xk_logistics_e2e");
            int runtimePort = runtime.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            try (var browser = new UpgradeBrowser(runtimePort)) {
                upgradeData(browser.post("/auth/login", java.util.Map.of(
                        "username", "synthetic-v8-upgrade-admin", "password", adminPassword), null), 200, "0000");
                JsonNode first = accepted.get("tasks").get(0), dependent = accepted.get("tasks").get(1);
                assertThat(first.get("predecessorTaskIds")).isEmpty();
                assertThat(dependent.get("predecessorTaskIds")).contains(first.get("id"));
                String firstId = first.get("id").asString(), dependentId = dependent.get("id").asString();
                JsonNode tasks = upgradeData(browser.get("/tms/tasks?orderId=" + accepted.get("id").asString()
                        + "&size=100&state=ALL"), 200, "0000");
                assertThat(tasks.get("totalElements").asInt()).isEqualTo(accepted.get("tasks").size());
                var listed = new java.util.ArrayList<String>();
                for (JsonNode task : tasks.get("content")) {
                    listed.add(task.get("taskId").asString());
                    assertThat(task.get("generation").asLong()).isZero();
                    assertThat(task.get("orderVersion").asLong()).isEqualTo(1);
                    assertThat(task.get("currentAssignmentId").isNull()).isTrue();
                    assertThat(task.get("policyMode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");
                }
                assertThat(listed).containsExactlyInAnyOrder(firstId, dependentId);
                JsonNode initial = upgradeData(browser.get("/tms/tasks/" + firstId + "/dispatch"), 200, "0000");
                assertThat(initial.get("task").get("prerequisiteReady").asBoolean()).isTrue();
                assertThat(initial.get("task").get("blockingReasons")).isEmpty();
                assertThat(initial.get("current").isNull()).isTrue();
                assertThat(initial.get("history").get("totalElements").asInt()).isZero();

                String categoryId = null;
                for (JsonNode category : upgradeData(browser.get("/adm/categories?size=100"), 200, "0000").get("content"))
                    if (category.get("code").asString().equals("VEHICLE_TYPE")) categoryId = category.get("id").asString();
                assertThat(categoryId).isNotNull();
                JsonNode type = upgradeData(browser.post("/adm/categories/" + categoryId + "/items", java.util.Map.of(
                        "code", "SYNTHETIC_TMS_IT_MAIN", "label", "合成升級派工主車類別", "enabled", true, "sortOrder", 1), null), 201, "0000");
                JsonNode driver = upgradeData(browser.post("/fms/drivers", java.util.Map.of(
                        "driverCode", "SYNTHETIC_TMS_IT_D1", "name", "合成升級派工司機", "dutyStatus", "ON_DUTY"), null), 201, "0000");
                assertThat(driver.get("employmentStatus").asString()).isEqualTo("ACTIVE");
                assertThat(driver.get("dutyStatus").asString()).isEqualTo("ON_DUTY");
                assertThat(driver.get("accountId").isNull()).isTrue();
                JsonNode vehicle = upgradeData(browser.post("/fms/vehicles", java.util.Map.of(
                        "plateNo", "SYN-" + java.util.UUID.randomUUID().toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT),
                        "vehicleType", type.get("id").asString(), "capacityTon", "12.3400"), null), 201, "0000");
                assertThat(vehicle.get("administrativeStatus").asString()).isEqualTo("AVAILABLE");

                var command = json.createObjectNode().put("expectedGeneration", 0)
                        .put("driverId", driver.get("id").asString()).put("mainVehicleId", vehicle.get("id").asString()).putNull("trailerId");
                String key = java.util.UUID.randomUUID().toString(), path = "/tms/tasks/" + firstId + "/dispatch";
                var assignedReply = browser.post(path, command, key);
                JsonNode assigned = upgradeData(assignedReply, 200, "0000");
                assertThat(assignedReply.headers().firstValue("Idempotency-Replayed")).contains("false");
                assertThat(assigned.get("taskId")).isEqualTo(first.get("id"));
                assertThat(assigned.get("generation").asLong()).isEqualTo(1);
                JsonNode assignment = assigned.get("assignment");
                assertThat(assignment.get("status").asString()).isEqualTo("ASSIGNED");
                assertThat(assignment.get("assignedBy").asString()).isEqualTo(actor.toString());
                assertThat(assignment.get("policyMode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");
                assertThat(assignment.get("driver").get("id")).isEqualTo(driver.get("id"));
                assertThat(assignment.get("mainVehicle").get("id")).isEqualTo(vehicle.get("id"));
                assertThat(assignment.get("trailer").isNull()).isTrue();
                assertThat(assignment.get("reservations")).hasSize(2);
                assertThat(assignment.get("events")).hasSize(1);
                assertThat(assignment.get("events").get(0).get("actorId").asString()).isEqualTo(actor.toString());
                assertThat(assignment.get("source")).isEqualTo(upgradeSource(accepted, first));
                assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_assignment", Long.class)).isEqualTo(1);
                assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL", Long.class)).isEqualTo(2);
                assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_dispatch_event", Long.class)).isEqualTo(1);
                assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_idempotency", Long.class)).isEqualTo(1);
                assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_dispatch_event WHERE actor_id=? AND task_id=? AND action='ASSIGNED' AND from_generation=0 AND to_generation=1",
                        Long.class, actor, java.util.UUID.fromString(firstId))).isEqualTo(1);
                var afterAssignment = new java.util.LinkedHashMap<String, String>();
                for (String table : java.util.List.of("tms_task_dispatch", "tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency"))
                    afterAssignment.put(table, tableDigest(database, table, table.equals("tms_task_dispatch") ? "task_id" : "id"));
                var replay = browser.post(path, command, key);
                assertThat(upgradeData(replay, 200, "0000")).isEqualTo(assigned);
                assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
                JsonNode current = upgradeData(browser.get(path), 200, "0000");
                assertThat(current.get("task").get("generation").asLong()).isEqualTo(1);
                assertThat(current.get("current")).isEqualTo(assignment);
                assertThat(current.get("history").get("totalElements").asInt()).isEqualTo(1);

                JsonNode blocked = upgradeData(browser.get("/tms/tasks/" + dependentId + "/dispatch"), 200, "0000");
                assertThat(blocked.get("task").get("prerequisiteReady").asBoolean()).isFalse();
                assertThat(blocked.get("task").get("blockingReasons").toString()).contains("PREREQUISITE_NOT_READY");
                assertThat(blocked.get("task").get("generation").asLong()).isZero();
                assertThat(blocked.get("current").isNull()).isTrue();
                assertThat(upgradeData(browser.post("/tms/tasks/" + dependentId + "/dispatch", command, java.util.UUID.randomUUID().toString()),
                        409, "E1604").get("reason").asString()).isEqualTo("PREREQUISITE_NOT_READY");
                afterAssignment.forEach((table, digest) -> assertThat(tableDigest(database, table, table.equals("tms_task_dispatch") ? "task_id" : "id"))
                        .as("Replay, reads and blocked dependent task preserve %s", table).isEqualTo(digest));
                assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_task_dispatch WHERE company_scope='SINGLE_COMPANY'", Long.class))
                        .isEqualTo(accepted.get("tasks").size());
                for (String table : java.util.List.of("order_core", "tom_order", "cargo_item", "transport_task", "task_dependency",
                        "order_draft_revision", "api_idempotency", "order_acceptance_event", "crm_customer", "crm_contact", "crm_address"))
                    assertThat(tableDigest(database, table, tableOrders.get(table)))
                            .as("Real migrated-task HTTP dispatch preserves all original TOM/CRM bytes in %s", table).isEqualTo(beforeMigration.get(table));
                assertNoExecutionRows(database);
                assertNoEvidenceRows(database);
                assertNoExceptionRows(database);
                assertNoCompletionFacts(database);
                assertNoManagementClosureFacts(database);
            }
        }
    }

    private JsonNode upgradeSource(JsonNode accepted, JsonNode first) {
        var source = json.createObjectNode();
        source.set("taskId", first.get("id"));
        source.set("orderId", accepted.get("id"));
        source.set("orderNo", accepted.get("orderNo"));
        source.set("orderType", accepted.get("type"));
        source.set("orderVersion", accepted.get("version"));
        for (String field : java.util.List.of("taskNo", "taskKind", "pickupName", "pickupAddress", "deliveryName", "deliveryAddress",
                "siteContact", "plannedStartAt", "plannedEndAt", "requiredTask")) source.set(field, first.get(field));
        var item = json.createObjectNode();
        for (JsonNode cargo : accepted.get("items")) if (cargo.get("id").equals(first.get("itemId")))
            for (String field : java.util.List.of("itemKind", "containerType", "containerNo", "packageQty", "grossWeight", "cbm"))
                item.set(field, cargo.get(field));
        assertThat(item).hasSize(6);
        source.set("item", item);
        return source;
    }

    private JsonNode upgradeData(HttpResponse<String> reply, int status, String code) {
        JsonNode envelope = json.readTree(reply.body());
        assertThat(reply.statusCode()).as("Upgrade HTTP response code %s", envelope.get("code").asString()).isEqualTo(status);
        assertThat(envelope.propertyNames()).containsExactlyInAnyOrder("code", "message", "data", "timestamp");
        assertThat(envelope.get("code").asString()).isEqualTo(code);
        assertThat(envelope.get("message").asString()).isNotBlank();
        assertThat(OffsetDateTime.parse(envelope.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        return envelope.get("data");
    }

    private final class UpgradeBrowser implements AutoCloseable {
        private final int runtimePort;
        private final HttpClient https = HttpClient.newBuilder()
                .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
                .sslContext(com.xksystem.logistics.support.TlsTestSupport.trustedContext())
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        UpgradeBrowser(int runtimePort) { this.runtimePort = runtimePort; }
        HttpResponse<String> get(String path) throws Exception { return send(path, null, null, null); }
        HttpResponse<String> post(String path, Object body, String key) throws Exception {
            String token = upgradeData(get("/auth/csrf"), 200, "0000").get("token").asString();
            return send(path, json.writeValueAsString(body), key, token);
        }
        private HttpResponse<String> send(String path, String body, String key, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + runtimePort + "/api/v1" + path))
                    .timeout(java.time.Duration.ofSeconds(30)).header("Accept", "application/json");
            if (body != null) request.header("Content-Type", "application/json");
            if (key != null) request.header("Idempotency-Key", key);
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return https.send(body == null ? request.GET().build() : request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() throws InterruptedException {
            https.shutdownNow();
            assertThat(https.awaitTermination(java.time.Duration.ofSeconds(5))).as("Temporary upgrade HTTP client terminates").isTrue();
        }
    }

    /** Valid historical V8 source graphs, with original successful receipts and immutable acceptance snapshots. */
    private JsonNode seedV8UpgradeOrder(JdbcTemplate database, java.util.UUID actor, java.util.UUID customer,
            java.time.OffsetDateTime at, String lifecycle, String acceptance, String label) {
        var snapshot = (tools.jackson.databind.node.ObjectNode) com.xksystem.logistics.support.TomContract
                .example("schemaPositive", "DEMO-LOCAL-CONTRACT-response").get("value").get("data").deepCopy();
        java.util.UUID order = java.util.UUID.randomUUID();
        snapshot.put("id", order.toString()).put("orderNo", "SYNTHETIC-V8-" + label)
                .put("customerId", customer.toString()).put("customerName", "合成歷史客戶")
                .put("createdBy", actor.toString()).put("updatedBy", actor.toString())
                .put("createdAt", at.toString()).put("updatedAt", at.toString())
                .put("remark", "合成\u0000V8 升級原值 e\u0301 ").put("acceptanceStatus", acceptance)
                .put("lifecycleStatus", lifecycle).put("version", acceptance.equals("ACCEPTED") ? 1 : 0);
        var items = new java.util.HashMap<String, java.util.UUID>();
        for (JsonNode raw : snapshot.get("items")) {
            var cargo = (tools.jackson.databind.node.ObjectNode) raw;
            java.util.UUID id = java.util.UUID.randomUUID();
            items.put(cargo.get("id").asString(), id);
            cargo.put("id", id.toString()).put("grossWeight", "0.0000000000000000001").put("cbm", "0");
        }
        var tasks = new java.util.HashMap<String, java.util.UUID>();
        var taskRefs = new java.util.HashMap<String, java.util.UUID>();
        for (JsonNode raw : snapshot.get("tasks")) {
            java.util.UUID id = java.util.UUID.randomUUID();
            tasks.put(raw.get("id").asString(), id);
            taskRefs.put(raw.get("clientRef").asString(), id);
        }
        int position = 0;
        for (JsonNode raw : snapshot.get("tasks")) {
            var task = (tools.jackson.databind.node.ObjectNode) raw;
            java.util.UUID id = tasks.get(task.get("id").asString());
            task.put("id", id.toString()).put("taskNo", "SYNTHETIC-V8-" + label + "-TASK-" + position++)
                    .put("orderId", order.toString()).put("itemId", items.get(task.get("itemId").asString()).toString());
            var predecessors = task.putArray("predecessorTaskIds");
            for (JsonNode ref : task.get("predecessorRefs")) predecessors.add(taskRefs.get(ref.asString()).toString());
        }
        var firstTask = (tools.jackson.databind.node.ObjectNode) snapshot.get("tasks").get(0);
        if (label.equals("ACCEPTED")) firstTask.put("taskKind", "SYNTHETIC_TMS_IT_UPGRADE_FIRST");
        firstTask.put("pickupAddress", "合成\u0000地址").put("plannedStartAt", "1500-01-02T00:00:00.123456+08:00")
                .put("plannedEndAt", "1500-01-02T01:00:00.123456+08:00");
        firstTask.putObject("siteContact").putNull("name").putNull("phone");
        com.xksystem.logistics.support.TomContract.assertSchema("OrderDraftRead", snapshot);
        database.update("INSERT INTO logistics.order_core(id,company_scope,order_no,lifecycle_status,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,?,?,?,?,?)",
                order, snapshot.get("orderNo").asString(), lifecycle, at, at, actor, actor);
        database.update("INSERT INTO logistics.tom_order(order_id,order_type,customer_id,customer_name,acceptance_status,version,remark,shipping_present,created_at,updated_at,created_by,updated_by) VALUES (?,'LOCAL',?,?,?,?,?,false,?,?,?,?)",
                order, customer, snapshot.get("customerName").asString(), acceptance, snapshot.get("version").asInt(),
                snapshot.get("remark").asString().getBytes(java.nio.charset.StandardCharsets.UTF_8), at, at, actor, actor);
        position = 0;
        for (JsonNode cargo : snapshot.get("items"))
            database.update("INSERT INTO logistics.cargo_item(id,order_id,client_ref,input_position,sequence,item_kind,package_qty,gross_weight,cbm,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,'CARGO',?,?,?,?,?,?,?)",
                    java.util.UUID.fromString(cargo.get("id").asString()), order, cargo.get("clientRef").asString(), position++, cargo.get("sequence").asInt(),
                    cargo.get("packageQty").asInt(), new java.math.BigDecimal(cargo.get("grossWeight").asString()), new java.math.BigDecimal(cargo.get("cbm").asString()), at, at, actor, actor);
        position = 0;
        for (JsonNode task : snapshot.get("tasks")) {
            database.update("INSERT INTO logistics.transport_task(id,task_no,order_id,item_id,client_ref,input_position,task_kind,pickup_name,pickup_address,delivery_name,delivery_address,contact_present,planned_start_at,planned_end_at,planned_start_wire,planned_end_wire,required_task,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,?,?,?,?)",
                    java.util.UUID.fromString(task.get("id").asString()), task.get("taskNo").asString(), order,
                    java.util.UUID.fromString(task.get("itemId").asString()), task.get("clientRef").asString(), position++, bytes(task, "taskKind"),
                    bytes(task, "pickupName"), bytes(task, "pickupAddress"), bytes(task, "deliveryName"), bytes(task, "deliveryAddress"), !task.get("siteContact").isNull(),
                    java.time.OffsetDateTime.parse(task.get("plannedStartAt").asString()), java.time.OffsetDateTime.parse(task.get("plannedEndAt").asString()),
                    task.get("plannedStartAt").asString(), task.get("plannedEndAt").asString(), at, at, actor, actor);
            int predecessorPosition = 0;
            for (JsonNode predecessor : task.get("predecessorTaskIds"))
                database.update("INSERT INTO logistics.task_dependency(order_id,task_id,predecessor_task_id,input_position,created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",
                        order, java.util.UUID.fromString(task.get("id").asString()), java.util.UUID.fromString(predecessor.asString()), predecessorPosition++, at, at, actor, actor);
        }
        var draft = snapshot.deepCopy().put("lifecycleStatus", "OPEN").put("acceptanceStatus", "DRAFT").put("version", 0);
        String draftJson = json.writeValueAsString(draft);
        var originalRequest = draft.deepCopy();
        for (String field : java.util.List.of("id", "orderNo", "customerName", "lifecycleStatus", "acceptanceStatus", "version",
                "createdAt", "updatedAt", "createdBy", "updatedBy")) originalRequest.remove(field);
        for (JsonNode raw : originalRequest.get("items")) ((tools.jackson.databind.node.ObjectNode) raw).remove("id");
        for (JsonNode raw : originalRequest.get("tasks"))
            for (String field : java.util.List.of("id", "taskNo", "orderId", "itemId", "predecessorTaskIds"))
                ((tools.jackson.databind.node.ObjectNode) raw).remove(field);
        String requestHash = com.xksystem.logistics.tom.domain.DraftCodec.canonicalHash(originalRequest);
        database.update("INSERT INTO logistics.order_draft_revision(id,order_id,aggregate_version,snapshot,request_trace_id,created_at,updated_at,created_by,updated_by) VALUES (?,?,0,?::json,'synthetic-v8-draft',?,?,?,?)",
                java.util.UUID.randomUUID(), order, draftJson, at, at, actor, actor);
        database.update("INSERT INTO logistics.api_idempotency(id,company_scope,actor_id,http_method,canonical_path,idempotency_key,request_hash,order_id,original_status,original_data,original_location,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,'POST','/api/v1/orders',?,?,?,201,?::json,?,?,?,?,?)",
                java.util.UUID.randomUUID(), actor, "synthetic-v8-create-" + label, requestHash, order, draftJson, "/api/v1/orders/" + order, at, at, actor, actor);
        if (acceptance.equals("ACCEPTED")) {
            var accepted = snapshot.deepCopy().put("lifecycleStatus", "OPEN");
            String acceptedJson = json.writeValueAsString(accepted);
            database.update("INSERT INTO logistics.order_draft_revision(id,order_id,aggregate_version,snapshot,request_trace_id,created_at,updated_at,created_by,updated_by) VALUES (?,?,1,?::json,'synthetic-v8-acceptance',?,?,?,?)",
                    java.util.UUID.randomUUID(), order, acceptedJson, at, at, actor, actor);
            database.update("INSERT INTO logistics.order_acceptance_event(id,order_id,from_version,to_version,actor_id,accepted_at,snapshot,request_trace_id) VALUES (?,?,0,1,?,?,?::json,'synthetic-v8-acceptance')",
                    java.util.UUID.randomUUID(), order, actor, at, acceptedJson);
            database.update("INSERT INTO logistics.api_idempotency(id,company_scope,actor_id,http_method,canonical_path,idempotency_key,request_hash,order_id,original_status,original_data,created_at,updated_at,created_by,updated_by) VALUES (?,'SINGLE_COMPANY',?,'POST',?,?,?,?,200,?::json,?,?,?,?)",
                    java.util.UUID.randomUUID(), actor, "/api/v1/orders/" + order + "/accept", "synthetic-v8-accept-" + label, com.xksystem.logistics.tom.domain.DraftCodec.canonicalHash(json.createObjectNode().put("expectedVersion", 0)), order, acceptedJson, at, at, actor, actor);
        }
        return snapshot;
    }

    private static byte[] bytes(JsonNode value,String field){return value.get(field).asString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    /** V13 adds a nullable relation; compare all original reservation columns without decoding JSON snapshots through jsonb. */
    private static String preservedDomainDigest(JdbcTemplate database, String table, String order) {
        if (!table.equals("tms_resource_reservation")) return tableDigest(database, table, order);
        return database.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY " + order + ")::text,'[]')) FROM "
                + "(SELECT id,assignment_id,driver_id,vehicle_id,resource_kind,resource_id,position,acquired_at,released_at,release_reason "
                + "FROM logistics.tms_resource_reservation) t", String.class);
    }

    private static void assertNoCompletionFacts(JdbcTemplate database) {
        for (String table : java.util.List.of("tms_completion", "tms_completion_command"))
            assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                    .as("No completion, safety decision, release time or receipt is backfilled in %s", table).isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE completion_id IS NOT NULL", Long.class))
                .as("Every legacy reservation initially has a NULL additive completion relation").isZero();
    }

    private static java.util.Map<String, String> v13DomainTableOrders() {
        var result = new java.util.LinkedHashMap<>(v12DomainTableOrders());
        result.put("tms_completion", "id");
        result.put("tms_completion_command", "id");
        return result;
    }

    private static void assertNoManagementClosureFacts(JdbcTemplate database) {
        for (String table : java.util.List.of("tms_completion_confirmation", "tms_completion_confirmation_command",
                "tom_order_closure", "tom_order_closure_task", "tom_order_closure_command"))
            assertThat(database.queryForObject("SELECT count(*) FROM logistics." + table, Long.class))
                    .as("No administrative confirmation, closure proof or history is backfilled in %s", table).isZero();
    }

    private static String tableDigest(JdbcTemplate database,String table,String order){return tableDigest(database,table,order,"");}
    private static String tableDigest(JdbcTemplate database,String table,String order,String predicate){return database.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+order+")::text,'[]')) FROM logistics."+table+" t"+predicate,String.class);}

}
