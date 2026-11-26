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
                        "crm_customer", "crm_contact", "crm_address", "order_core", "tom_order", "cargo_item", "transport_task",
                        "task_dependency", "order_draft_revision", "api_idempotency", "fms_vehicle", "fms_driver", "order_acceptance_event",
                        "tms_task_dispatch", "tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency");
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
        assertThat(jdbc.queryForList("select code from logistics.adm_dict_category order by sort_order", String.class))
                .containsExactly("ORDER_TYPE", "CONTAINER_TYPE", "TASK_KIND", "VEHICLE_TYPE", "DRIVER_LICENSE_TYPE");
        assertThat(jdbc.queryForList("select code from logistics.adm_dict_item order by sort_order", String.class))
                .containsExactly("IMPORT", "EXPORT", "LOCAL");
        assertThat(jdbc.queryForObject("select count(*) from logistics.adm_dict_item where not read_only", Long.class)).isZero();
        for (String table : new String[] {"adm_parameter_definition", "adm_parameter", "adm_change_log", "crm_customer", "crm_contact", "crm_address",
                "order_core", "tom_order", "cargo_item", "transport_task", "task_dependency", "order_draft_revision", "api_idempotency", "fms_vehicle", "fms_driver", "order_acceptance_event",
                "tms_task_dispatch", "tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency"}) {
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
        String databaseName = "xk_tms_upgrade_test";
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
            var at = java.time.OffsetDateTime.parse("2026-11-17T08:00:00.123456Z");
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword())) {
                var encoder = new com.xksystem.logistics.upms.UpmsConfiguration().passwordEncoder();
                actor = com.xksystem.logistics.support.UpmsTestData.insert(connection, encoder,
                        "synthetic-v8-upgrade-admin", "ADMIN", com.xksystem.logistics.support.UpmsTestData.password(), false, at.toInstant());
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
        } finally {
            jdbc.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
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
    private static String tableDigest(JdbcTemplate database,String table,String order){return tableDigest(database,table,order,"");}
    private static String tableDigest(JdbcTemplate database,String table,String order,String predicate){return database.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+order+")::text,'[]')) FROM logistics."+table+" t"+predicate,String.class);}

}
