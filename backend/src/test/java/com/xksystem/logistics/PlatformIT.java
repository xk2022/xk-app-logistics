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
                        "task_dependency", "order_draft_revision", "api_idempotency", "fms_vehicle", "fms_driver");
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
        assertThat(jdbc.queryForList("select code from logistics.adm_dict_category order by sort_order", String.class))
                .containsExactly("ORDER_TYPE", "CONTAINER_TYPE", "TASK_KIND", "VEHICLE_TYPE", "DRIVER_LICENSE_TYPE");
        assertThat(jdbc.queryForList("select code from logistics.adm_dict_item order by sort_order", String.class))
                .containsExactly("IMPORT", "EXPORT", "LOCAL");
        assertThat(jdbc.queryForObject("select count(*) from logistics.adm_dict_item where not read_only", Long.class)).isZero();
        for (String table : new String[] {"adm_parameter_definition", "adm_parameter", "adm_change_log", "crm_customer", "crm_contact", "crm_address",
                "order_core", "tom_order", "cargo_item", "transport_task", "task_dependency", "order_draft_revision", "api_idempotency", "fms_vehicle", "fms_driver"}) {
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

    private static byte[] bytes(JsonNode value,String field){return value.get(field).asString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private static String tableDigest(JdbcTemplate database,String table,String order){return tableDigest(database,table,order,"");}
    private static String tableDigest(JdbcTemplate database,String table,String order,String predicate){return database.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+order+")::text,'[]')) FROM logistics."+table+" t"+predicate,String.class);}

}
