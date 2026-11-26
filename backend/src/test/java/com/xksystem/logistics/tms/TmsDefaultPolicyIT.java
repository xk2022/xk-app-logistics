package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.assertThat;

import com.xksystem.logistics.LogisticsApplication;
import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.TomContract;
import com.xksystem.logistics.support.UpmsTestData;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Ordinary application startup must remain closed even with synthetic test classes on its classpath. */
@Testcontainers
@SpringBootTest(classes = LogisticsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TmsDefaultPolicyIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres")).withStartupAttempts(3).withDatabaseName("xk_logistics_e2e");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        TlsTestSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired ApplicationContext application;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired DispatchEligibilityPolicy policy;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void ordinaryApplicationRejectsSyntheticLookingResourcesWithoutExplicitTestPolicyImport() throws Exception {
        // The database and classes satisfy the isolated test policy's environment boundary,
        // but ordinary component scanning must never opt into that configuration.
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("xk_logistics_e2e");
        assertThat(getClass().getClassLoader().getResource(
                "com/xksystem/logistics/support/SyntheticDispatchPolicyConfiguration.class")).isNotNull();
        var policies = application.getBeansOfType(DispatchEligibilityPolicy.class);
        assertThat(policies).hasSize(1);
        assertThat(policy).isSameAs(policies.values().iterator().next());
        assertThat(policy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);
        assertThat(application.containsBean("syntheticDispatchEligibilityPolicy")).isFalse();

        Account administrator = account("ADMIN"), driverAccount = account("DRIVER");
        var admin = new Browser();
        company(admin.write("POST", "/auth/login", fields("username", administrator.username(),
                "password", administrator.password()), null), 200, "0000");
        UUID mainType = dictionaryItem(admin, "SYNTHETIC_TMS_IT_MAIN", "合成預設政策主車類別");
        UUID trailerType = dictionaryItem(admin, "SYNTHETIC_TMS_IT_TRAILER", "合成預設政策拖板類別");
        JsonNode driver = company(admin.write("POST", "/fms/drivers", fields(
                "driverCode", "SYNTHETIC_TMS_IT_D1", "name", "合成預設政策司機", "dutyStatus", "ON_DUTY",
                "accountId", driverAccount.id().toString()), null), 201, "0000");
        JsonNode main = vehicle(admin, mainType), trailer = vehicle(admin, trailerType);
        assertThat(driver.get("employmentStatus").asString()).isEqualTo("ACTIVE");
        assertThat(driver.get("dutyStatus").asString()).isEqualTo("ON_DUTY");
        assertThat(main.get("administrativeStatus").asString()).isEqualTo("AVAILABLE");
        assertThat(trailer.get("administrativeStatus").asString()).isEqualTo("AVAILABLE");

        JsonNode customer = company(admin.write("POST", "/crm/customers", fields(
                "customerCode", "SYN-TMS-DEFAULT-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT),
                "name", "合成預設政策客戶，不是真實資料"), null), 201, "0000");
        ObjectNode draft = (ObjectNode) TomContract.example("graphCases", "DEMO-LOCAL-CONTRACT")
                .get("draft").deepCopy();
        draft.put("customerId", id(customer));
        for (JsonNode task : draft.get("tasks"))
            ((ObjectNode) task).put("taskKind", "SYNTHETIC_TMS_IT_" + task.get("clientRef").asString());
        JsonNode created = tom(admin.write("POST", "/orders", draft, key()), 201);
        JsonNode accepted = tom(admin.write("POST", "/orders/" + id(created) + "/accept",
                fields("expectedVersion", 0), key()), 200);
        assertThat(accepted.get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
        JsonNode first = null;
        for (JsonNode task : accepted.get("tasks")) if (task.get("predecessorRefs").isEmpty()) first = task;
        assertThat(first).isNotNull();
        UUID taskId = UUID.fromString(id(first));

        JsonNode tasks = company(admin.get("/tms/tasks?orderId=" + id(accepted) + "&size=100"), 200, "0000");
        assertThat(tasks.get("totalElements").asLong()).isEqualTo(accepted.get("tasks").size());
        JsonNode summary = null;
        for (JsonNode task : tasks.get("content")) if (task.get("taskId").asString().equals(taskId.toString())) summary = task;
        assertThat(summary).isNotNull();
        assertThat(summary.get("generation").asLong()).isZero();
        assertThat(summary.get("prerequisiteReady").asBoolean()).isTrue();
        assertThat(summary.get("currentAssignmentId").isNull()).isTrue();
        assertThat(summary.get("policyMode").asString()).isEqualTo("UNCONFIRMED");
        assertThat(summary.get("blockingReasons")).anySatisfy(reason ->
                assertThat(reason.asString()).isEqualTo("POLICY_NOT_CONFIRMED"));
        assertCandidatePolicyClosed(admin, taskId, "DRIVER", driver, driver.get("driverCode").asString());
        assertCandidatePolicyClosed(admin, taskId, "MAIN", main, main.get("plateNo").asString());
        assertCandidatePolicyClosed(admin, taskId, "TRAILER", trailer, trailer.get("plateNo").asString());

        Map<String, Object> request = fields("expectedGeneration", 0, "driverId", id(driver),
                "mainVehicleId", id(main), "trailerId", id(trailer));
        String requestKey = key();
        for (int attempt = 0; attempt < 2; attempt++) {
            JsonNode error = company(admin.write("POST", "/tms/tasks/" + taskId + "/dispatch", request, requestKey), 409, "E1605");
            assertThat(error.get("reason").asString()).isEqualTo("POLICY_NOT_CONFIRMED");
            assertNoDispatchMutation(taskId, accepted.get("tasks").size());
        }
        JsonNode detail = company(admin.get("/tms/tasks/" + taskId + "/dispatch"), 200, "0000");
        assertThat(detail.get("current").isNull()).isTrue();
        assertThat(detail.get("history").get("content")).isEmpty();
        assertThat(detail.get("history").get("totalElements").asLong()).isZero();
        assertThat(detail.get("task").get("generation").asLong()).isZero();
        assertThat(policy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);
    }

    private void assertCandidatePolicyClosed(Browser admin, UUID task, String position, JsonNode resource, String search) throws Exception {
        JsonNode page = company(admin.get("/tms/resources?taskId=" + task + "&position=" + position
                + "&q=" + URLEncoder.encode(search, StandardCharsets.UTF_8) + "&size=100"), 200, "0000");
        assertThat(page.get("content")).hasSize(1);
        JsonNode candidate = page.get("content").get(0);
        assertThat(candidate.get("id").asString()).isEqualTo(id(resource));
        assertThat(candidate.get("administrativelyAvailable").asBoolean()).isTrue();
        assertThat(candidate.get("occupied").asBoolean()).isFalse();
        assertThat(candidate.get("occupation").isNull()).isTrue();
        assertThat(candidate.get("policyMode").asString()).isEqualTo("UNCONFIRMED");
        assertThat(candidate.get("policyApproved").asBoolean()).isFalse();
        var reasons = new java.util.ArrayList<String>();
        candidate.get("blockingReasons").forEach(reason -> reasons.add(reason.asString()));
        assertThat(reasons).contains("POLICY_NOT_CONFIRMED")
                .doesNotContain("ADMIN_UNAVAILABLE", "RESOURCE_OCCUPIED", "SOURCE_NOT_ACCEPTED", "PREREQUISITE_NOT_READY", "ALREADY_ASSIGNED");
    }

    private void assertNoDispatchMutation(UUID task, int sourceTaskCount) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.tms_task_dispatch", Long.class)).isEqualTo(sourceTaskCount);
        assertThat(jdbc.queryForObject("SELECT generation FROM logistics.tms_task_dispatch WHERE task_id=?", Long.class, task)).isZero();
        for (String table : List.of("tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics." + table, Long.class)).as("No dispatch write in %s", table).isZero();
    }

    private UUID dictionaryItem(Browser admin, String code, String label) throws Exception {
        JsonNode page = company(admin.get("/adm/categories?size=100"), 200, "0000");
        UUID category = null;
        for (JsonNode value : page.get("content")) if (value.get("code").asString().equals("VEHICLE_TYPE")) category = UUID.fromString(id(value));
        assertThat(category).isNotNull();
        return UUID.fromString(id(company(admin.write("POST", "/adm/categories/" + category + "/items",
                fields("code", code, "label", label, "enabled", true, "sortOrder", 1), null), 201, "0000")));
    }

    private JsonNode vehicle(Browser admin, UUID type) throws Exception {
        return company(admin.write("POST", "/fms/vehicles", fields("plateNo", "SYN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT),
                "vehicleType", type.toString(), "capacityTon", "12.3400"), null), 201, "0000");
    }

    private JsonNode company(HttpResponse<String> reply, int status, String code) {
        JsonNode body = json.readTree(reply.body());
        assertThat(reply.statusCode()).as("HTTP status; code %s", body.get("code").asString()).isEqualTo(status);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "data", "timestamp");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        return body.get("data");
    }

    private JsonNode tom(HttpResponse<String> reply, int status) {
        JsonNode body = json.readTree(reply.body());
        assertThat(reply.statusCode()).isEqualTo(status);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "data", "traceId");
        assertThat(body.get("code").asString()).isEqualTo("OK");
        assertThat(body.get("traceId").asString()).isNotBlank();
        return body.get("data");
    }

    private Account account(String role) throws Exception {
        String username = "synthetic-tms-default-" + role.toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID().toString().substring(0, 12);
        String password = UpmsTestData.password();
        try (var connection = dataSource.getConnection()) {
            return new Account(UpmsTestData.insert(connection, encoder, username, role, password, false, Instant.now()), username, password);
        }
    }

    private static Map<String, Object> fields(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
    private String id(JsonNode value) { return value.get("id").asString(); }
    private String key() { return UUID.randomUUID().toString(); }
    private record Account(UUID id, String username, String password) {}

    private final class Browser {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .sslContext(TlsTestSupport.trustedContext()).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }
        HttpResponse<String> write(String method, String path, Object payload, String key) throws Exception {
            JsonNode csrf = company(get("/auth/csrf"), 200, "0000");
            return send(method, path, json.writeValueAsString(payload), key, csrf.get("token").asString());
        }
        private HttpResponse<String> send(String method, String path, String payload, String key, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
            if (payload != null) request.header("Content-Type", "application/json");
            if (key != null) request.header("Idempotency-Key", key);
            if (token != null) request.header("X-CSRF-TOKEN", token);
            return client.send(request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
