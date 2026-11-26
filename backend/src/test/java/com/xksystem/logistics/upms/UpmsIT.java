package com.xksystem.logistics.upms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.support.MutableTestClock;
import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.UpmsTestData;
import com.xksystem.logistics.upms.internal.UpmsService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

/** Actual HTTPS and PostgreSQL tests; no mocked authentication or authorization filters. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(UpmsIT.TimeConfiguration.class)
class UpmsIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        TlsTestSupport.register(registry);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock();
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired UpmsService service;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void isolateSyntheticAccounts() {
        jdbc.execute("TRUNCATE TABLE logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle, logistics.order_core, logistics.tom_order, logistics.cargo_item, logistics.transport_task, "
                + "logistics.task_dependency, logistics.order_draft_revision, logistics.api_idempotency, "
                + "logistics.crm_contact, logistics.crm_address, logistics.crm_customer, "
                + "logistics.adm_change_log, logistics.upms_audit, logistics.upms_account");
        clock.reset();
    }

    @Test
    void bootstrapIsOfflineOnlyAndSerializesConcurrentFirstAdministrators() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> initialize = () -> {
                try {
                    service.bootstrap(username(), "合成初始管理員", UpmsTestData.password(), "test-console", "synthetic test");
                    return true;
                } catch (RuntimeException rejected) {
                    return false;
                }
            };
            var results = pool.invokeAll(List.of(initialize, initialize));
            assertThat(results.stream().filter(result -> {
                try { return result.get(); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }).count()).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM logistics.upms_account WHERE role='ADMIN' AND enabled AND must_change_password"))
                .isEqualTo(1);
        assertThatThrownBy(() -> service.bootstrap(username(), "額外初始化", UpmsTestData.password(), "test", "synthetic"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM logistics.upms_account")).isEqualTo(1);
        Browser anonymous = new Browser();
        assertThat(anonymous.get("/api/v1/upms/bootstrap").statusCode()).isIn(401, 404);
        assertThat(anonymous.post("/api/v1/upms/bootstrap", Map.of(), true).statusCode()).isIn(401, 403, 404);
        jdbc.execute("TRUNCATE TABLE logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle, logistics.order_core, logistics.tom_order, logistics.cargo_item, logistics.transport_task, "
                + "logistics.task_dependency, logistics.order_draft_revision, logistics.api_idempotency, "
                + "logistics.crm_contact, logistics.crm_address, logistics.crm_customer, "
                + "logistics.adm_change_log, logistics.upms_audit, logistics.upms_account");
        account("DRIVER", false);
        assertThatThrownBy(() -> service.bootstrap(username(), "不得覆蓋非空庫", UpmsTestData.password(), "test", "synthetic"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM logistics.upms_account WHERE role='DRIVER'")).isEqualTo(1);
    }

    @Test
    void allThreeRolesLoginReadTheirOwnAccountAndLogout() throws Exception {
        for (String role : List.of("ADMIN", "DISPATCHER", "DRIVER")) {
            Account account = account(role, false);
            Browser browser = new Browser();
            assertStatus(browser.get("/api/v1/auth/me"), 401, "E0401");
            assertStatus(browser.login(account), 200, "0000");
            JsonNode me = data(browser.get("/api/v1/auth/me"));
            assertThat(me.get("id").asString()).isEqualTo(account.id().toString());
            assertThat(me.get("role").asString()).isEqualTo(role);
            assertThat(me.get("mustChangePassword").asBoolean()).isFalse();
            assertStatus(browser.post("/api/v1/auth/logout", Map.of(), true), 200, "0000");
            assertStatus(browser.get("/api/v1/auth/me"), 401, "E0401");
        }
    }

    @Test
    void invalidSubmittedIdentitiesCannotAliasARealAccount() throws Exception {
        Account original = account("DRIVER", false);
        jdbc.update("UPDATE logistics.upms_account SET username='invalid-identity' WHERE id=?", original.id());
        Account real = new Account(original.id(), "invalid-identity", original.password());
        Browser browser = new Browser();
        assertStatus(browser.post("/api/v1/auth/login", Map.of("password", real.password()), true), 401, "E0401");
        assertStatus(browser.post("/api/v1/auth/login", Map.of("username", "x".repeat(257),
                "password", real.password()), true), 401, "E0401");
        assertStatus(browser.login(real), 200, "0000");
    }

    @Test
    void malformedStoredHashesFailClosedWithTheSameUnauthorizedEnvelope() throws Exception {
        Account worker = account("DRIVER", false);
        Browser browser = new Browser();
        for (String malformed : List.of("{noop}synthetic-corrupt-value", "{pbkdf2-sha256-v1}not-hex")) {
            jdbc.update("UPDATE logistics.upms_account SET password_hash=? WHERE id=?", malformed, worker.id());
            assertStatus(browser.login(worker), 401, "E0401");
        }
    }

    @Test
    void unavailableDatabaseReturnsSanitized503ForAuthenticatedAndAnonymousRequests() throws Exception {
        Account worker = account("DRIVER", false);
        Browser authenticated = new Browser();
        assertStatus(authenticated.login(worker), 200, "0000");
        Browser anonymous = new Browser();
        String token = anonymous.csrf();
        String administrativeUrl = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/postgres");
        try (var connection = java.sql.DriverManager.getConnection(administrativeUrl, postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("ALTER DATABASE test WITH ALLOW_CONNECTIONS false");
            try {
                statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='test'");
                assertStatus(authenticated.get("/api/v1/auth/me"), 503, "E0503");
                assertStatus(anonymous.send("POST", "/api/v1/auth/login", credentials(worker), token), 503, "E0503");
                assertStatus(anonymous.get("/api/v1/status"), 503, "E0503");
            } finally {
                statement.execute("ALTER DATABASE test WITH ALLOW_CONNECTIONS true");
            }
        }
        // Restore the same isolated database before another test uses the shared application context.
        RuntimeException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                return;
            } catch (org.springframework.dao.DataAccessException unavailable) {
                last = unavailable;
            }
        }
        throw new IllegalStateException("Isolated test database did not become available after restoration", last);
    }

    @Test
    void loginRotatesSessionAndCookiesAreSecureHttpOnlyAndSameSiteLax() throws Exception {
        Account account = account("ADMIN", false);
        Browser browser = new Browser();
        String oldToken = browser.csrf();
        String anonymousSession = browser.sessionId();
        HttpResponse<String> login = browser.send("POST", "/api/v1/auth/login",
                Map.of("username", account.username(), "password", account.password()), oldToken);
        assertStatus(login, 200, "0000");
        assertThat(browser.sessionId()).isNotBlank().isNotEqualTo(anonymousSession);
        String cookie = String.join(";", login.headers().allValues("set-cookie"));
        assertThat(cookie).containsIgnoringCase("Secure").containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax");
        assertStatus(browser.send("POST", "/api/v1/auth/logout", Map.of(), oldToken), 403, "E0403");
        assertStatus(browser.post("/api/v1/auth/logout", Map.of(), true), 200, "0000");
    }

    @Test
    void loginAndEveryAccountWriteRequireCsrfAndBusinessRoleIsEnforced() throws Exception {
        Account admin = account("ADMIN", false);
        Browser browser = new Browser();
        assertStatus(browser.post("/api/v1/auth/login", credentials(admin), false), 403, "E0403");
        assertStatus(browser.login(admin), 200, "0000");
        assertStatus(browser.post("/api/v1/upms/accounts", createBody("DRIVER"), false), 403, "E0403");
        assertStatus(browser.post("/api/v1/auth/password", Map.of("currentPassword", admin.password(),
                "newPassword", UpmsTestData.password()), false), 403, "E0403");
        assertStatus(browser.post("/api/v1/auth/logout", Map.of(), false), 403, "E0403");
        Browser anonymous = new Browser();
        assertStatus(anonymous.get("/api/v1/upms/accounts"), 401, "E0401");
        for (String role : List.of("DISPATCHER", "DRIVER")) {
            Browser worker = new Browser();
            assertStatus(worker.login(account(role, false)), 200, "0000");
            assertStatus(worker.get("/api/v1/upms/accounts"), 403, "E0403");
            assertStatus(worker.get("/api/v1/upms/roles"), 403, "E0403");
            assertStatus(worker.post("/api/v1/upms/accounts", createBody("ADMIN"), true), 403, "E0403");
        }
    }

    @Test
    void temporaryAccountsCanOnlyReadMeChangePasswordOrLogoutAndExpireAfterFifteenMinutes() throws Exception {
        Account temporary = account("ADMIN", true);
        Browser browser = new Browser();
        assertStatus(browser.login(temporary), 200, "0000");
        assertThat(data(browser.get("/api/v1/auth/me")).get("mustChangePassword").asBoolean()).isTrue();
        assertStatus(browser.get("/api/v1/upms/accounts"), 403, "E0403");
        assertStatus(browser.get("/api/v1/upms/roles"), 403, "E0403");
        assertStatus(browser.post("/api/v1/upms/accounts", createBody("DRIVER"), true), 403, "E0403");
        Browser limitedLogout = new Browser();
        assertStatus(limitedLogout.login(temporary), 200, "0000");
        assertStatus(limitedLogout.post("/api/v1/auth/logout", Map.of(), true), 200, "0000");
        assertStatus(limitedLogout.get("/api/v1/auth/me"), 401, "E0401");
        assertStatus(browser.get("/api/v1/auth/me"), 200, "0000");
        String replacement = UpmsTestData.password();
        assertStatus(browser.post("/api/v1/auth/password", Map.of("currentPassword", temporary.password(),
                "newPassword", replacement), true), 200, "0000");
        assertStatus(browser.get("/api/v1/auth/me"), 401, "E0401");
        assertStatus(new Browser().login(new Account(temporary.id(), temporary.username(), temporary.password())), 401, "E0401");
        Browser changed = new Browser();
        assertStatus(changed.login(new Account(temporary.id(), temporary.username(), replacement)), 200, "0000");
        assertThat(data(changed.get("/api/v1/auth/me")).get("mustChangePassword").asBoolean()).isFalse();

        Account expired = account("DRIVER", true);
        Browser expiredBrowser = new Browser();
        assertStatus(expiredBrowser.login(expired), 200, "0000");
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertStatus(expiredBrowser.get("/api/v1/auth/me"), 401, "E0401");
        assertStatus(new Browser().login(expired), 401, "E0401");
    }

    @Test
    void passwordsValidateUnicodeCodePointsNormalizeNfcAndUseIndependentSlowHashes() throws Exception {
        Account admin = account("ADMIN", false);
        Browser browser = new Browser();
        assertStatus(browser.login(admin), 200, "0000");
        for (String invalid : List.of("a".repeat(14), "a".repeat(129), "😀".repeat(14))) {
            Map<String, Object> request = createBody("DRIVER");
            request.put("password", invalid);
            assertStatus(browser.post("/api/v1/upms/accounts", request, true), 400, "E0400");
        }
        assertThat(count("SELECT count(*) FROM logistics.upms_account")).isEqualTo(1);
        String composed = "é".repeat(15);
        Map<String, Object> valid = createBody("DRIVER");
        valid.put("password", composed);
        HttpResponse<String> created = browser.post("/api/v1/upms/accounts", valid, true);
        assertStatus(created, 201, "0000");
        String targetUsername = (String) valid.get("username");
        UUID targetId = UUID.fromString(data(created).get("id").asString());
        assertStatus(new Browser().login(new Account(targetId, targetUsername, "e\u0301".repeat(15))), 200, "0000");
        String stored = jdbc.queryForObject("SELECT password_hash FROM logistics.upms_account WHERE id=?", String.class, targetId);
        assertThat(stored).startsWith("{pbkdf2-sha256-v1}");
        assertThat(stored.equals(composed)).isFalse();
        assertThat(encoder.matches(composed, stored)).isTrue();
        assertThat(encoder.encode(composed)).isNotEqualTo(stored);
        Map<String, Object> longest = createBody("DRIVER");
        longest.put("password", "😀".repeat(128));
        assertStatus(browser.post("/api/v1/upms/accounts", longest, true), 201, "0000");
    }

    @Test
    void adminManagesOnlyFixedRolesAndOptimisticVersionsAndResponsesNeverExposeHashes() throws Exception {
        Browser admin = new Browser();
        assertStatus(admin.login(account("ADMIN", false)), 200, "0000");
        JsonNode roles = data(admin.get("/api/v1/upms/roles"));
        assertThat(roles.size()).isEqualTo(3);
        assertThat(List.of(roles.get(0).asString(), roles.get(1).asString(), roles.get(2).asString()))
                .containsExactly("ADMIN", "DISPATCHER", "DRIVER");
        // The M1 absence check evolves with the now-authorized TOM slice; the UPMS role checks remain intact.
        HttpResponse<String> orders = admin.get("/api/v1/orders");
        assertThat(orders.statusCode()).isEqualTo(200);
        com.xksystem.logistics.support.TomContract.assertSchema("OrderPageEnvelope", json.readTree(orders.body()));
        Map<String, Object> create = createBody("DRIVER");
        HttpResponse<String> created = admin.post("/api/v1/upms/accounts", create, true);
        assertStatus(created, 201, "0000");
        JsonNode target = data(created);
        String id = target.get("id").asString();
        assertStatus(admin.post("/api/v1/upms/accounts", create, true), 409, "E0409");
        Map<String, Object> invalidRole = createBody("SUPER_ADMIN");
        assertStatus(admin.post("/api/v1/upms/accounts", invalidRole, true), 400, "E0400");
        Map<String, Object> patch = Map.of("displayName", "合成新名稱", "role", "DISPATCHER", "enabled", true,
                "version", target.get("version").asLong());
        assertStatus(admin.patch("/api/v1/upms/accounts/" + id, patch), 200, "0000");
        assertStatus(admin.patch("/api/v1/upms/accounts/" + id, patch), 409, "E0409");
        String accounts = admin.get("/api/v1/upms/accounts").body();
        assertThat(accounts).doesNotContain("password_hash", "passwordHash", "pbkdf2", "temporaryPassword", "authVersion");
        assertThat(accounts.contains((String) create.get("password"))).isFalse();
    }

    @Test
    void disabledAndRoleChangedAccountsLoseAllExistingSessionsImmediately() throws Exception {
        Browser admin = new Browser();
        assertStatus(admin.login(account("ADMIN", false)), 200, "0000");
        for (boolean disable : List.of(true, false)) {
            Account worker = account("DISPATCHER", false);
            Browser first = new Browser();
            Browser second = new Browser();
            assertStatus(first.login(worker), 200, "0000");
            assertStatus(second.login(worker), 200, "0000");
            assertStatus(admin.patch("/api/v1/upms/accounts/" + worker.id(), Map.of("displayName", "合成帳號",
                    "role", disable ? "DISPATCHER" : "DRIVER", "enabled", !disable, "version", 0)), 200, "0000");
            assertStatus(first.get("/api/v1/auth/me"), 401, "E0401");
            assertStatus(second.get("/api/v1/auth/me"), 401, "E0401");
            Browser retry = new Browser();
            assertStatus(retry.login(worker), disable ? 401 : 200, disable ? "E0401" : "0000");
            if (!disable) assertThat(data(retry.get("/api/v1/auth/me")).get("role").asString()).isEqualTo("DRIVER");
        }
    }

    @Test
    void passwordChangeAndControlledRecoveryRevokeEverySessionWithoutLeakingSecretsToAudit() throws Exception {
        Account worker = account("DISPATCHER", false);
        Browser first = new Browser();
        Browser second = new Browser();
        assertStatus(first.login(worker), 200, "0000");
        assertStatus(second.login(worker), 200, "0000");
        String replacement = UpmsTestData.password();
        assertStatus(first.post("/api/v1/auth/password", Map.of("currentPassword", worker.password(),
                "newPassword", replacement), true), 200, "0000");
        assertStatus(first.get("/api/v1/auth/me"), 401, "E0401");
        assertStatus(second.get("/api/v1/auth/me"), 401, "E0401");
        Browser current = new Browser();
        assertStatus(current.login(new Account(worker.id(), worker.username(), replacement)), 200, "0000");
        String recoveredPassword = UpmsTestData.password();
        service.recover(worker.username(), recoveredPassword, "controlled-test-console", "合成復原測試");
        assertStatus(current.get("/api/v1/auth/me"), 401, "E0401");
        assertStatus(new Browser().login(new Account(worker.id(), worker.username(), replacement)), 401, "E0401");
        Browser recovered = new Browser();
        assertStatus(recovered.login(new Account(worker.id(), worker.username(), recoveredPassword)), 200, "0000");
        assertThat(data(recovered.get("/api/v1/auth/me")).get("mustChangePassword").asBoolean()).isTrue();
        assertStatus(recovered.get("/api/v1/upms/accounts"), 403, "E0403");
        List<String> audits = jdbc.queryForList("SELECT row_to_json(a)::text FROM logistics.upms_audit a", String.class);
        assertThat(audits).isNotEmpty();
        assertThat(audits.stream().anyMatch(value -> value.contains(worker.password()) || value.contains(replacement)
                || value.contains(recoveredPassword) || value.contains("pbkdf2"))).isFalse();
    }

    @Test
    void fourthLoginEvictsOldestOfThreeConcurrentSessions() throws Exception {
        Account worker = account("DRIVER", false);
        List<Browser> browsers = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            clock.advance(Duration.ofSeconds(1));
            Browser browser = new Browser();
            assertStatus(browser.login(worker), 200, "0000");
            browsers.add(browser);
        }
        assertStatus(browsers.getFirst().get("/api/v1/auth/me"), 401, "E0401");
        for (Browser browser : browsers.subList(1, 4)) assertStatus(browser.get("/api/v1/auth/me"), 200, "0000");
    }

    @Test
    void idleThirtyMinutesAndAbsoluteEightHoursExpireWithoutChangingProductionTimeouts() throws Exception {
        Account worker = account("DRIVER", false);
        Browser idle = new Browser();
        assertStatus(idle.login(worker), 200, "0000");
        clock.advance(Duration.ofMinutes(29));
        assertStatus(idle.get("/api/v1/auth/me"), 200, "0000");
        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        assertStatus(idle.get("/api/v1/auth/me"), 401, "E0401");

        Browser absolute = new Browser();
        assertStatus(absolute.login(worker), 200, "0000");
        for (int index = 0; index < 16; index++) {
            clock.advance(Duration.ofMinutes(29));
            assertStatus(absolute.get("/api/v1/auth/me"), 200, "0000");
        }
        clock.advance(Duration.ofMinutes(17));
        assertStatus(absolute.get("/api/v1/auth/me"), 401, "E0401");
    }

    @Test
    void tenFailuresLockForFifteenMinutesAndUnknownAccountsHaveSameError() throws Exception {
        Account worker = account("DRIVER", false);
        Browser failing = new Browser();
        JsonNode firstError = null;
        for (int index = 0; index < 10; index++) {
            HttpResponse<String> response = failing.post("/api/v1/auth/login", Map.of("username", worker.username(),
                    "password", UpmsTestData.password()), true);
            assertStatus(response, 401, "E0401");
            if (firstError == null) firstError = json.readTree(response.body());
        }
        HttpResponse<String> locked = failing.login(worker);
        assertStatus(locked, 401, "E0401");
        HttpResponse<String> unknown = new Browser().post("/api/v1/auth/login", Map.of("username", username(),
                "password", UpmsTestData.password()), true);
        assertStatus(unknown, 401, "E0401");
        assertThat(json.readTree(unknown.body()).get("message").asString()).isEqualTo(firstError.get("message").asString());
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertStatus(failing.login(worker), 401, "E0401");
        clock.advance(Duration.ofSeconds(2));
        assertStatus(failing.login(worker), 200, "0000");
    }

    @Test
    void recoveryCommittedByAnotherProcessUnlocksTheRunningWebInstance() throws Exception {
        Account worker = account("DRIVER", false);
        Browser browser = new Browser();
        for (int index = 0; index < 10; index++) {
            assertStatus(browser.post("/api/v1/auth/login", Map.of("username", worker.username(),
                    "password", UpmsTestData.password()), true), 401, "E0401");
        }
        assertStatus(browser.login(worker), 401, "E0401");
        String newTemporary = UpmsTestData.password();
        // Deliberately bypass the web service bean and its in-memory limiter, as the offline CLI process does.
        jdbc.update("""
                UPDATE logistics.upms_account SET password_hash=?, must_change_password=true,
                    temporary_password_expires_at=?, auth_version=auth_version+1, version=version+1
                WHERE id=?
                """, encoder.encode(newTemporary), java.time.OffsetDateTime.ofInstant(clock.instant().plusSeconds(900),
                java.time.ZoneOffset.UTC), worker.id());
        assertStatus(browser.login(new Account(worker.id(), worker.username(), newTemporary)), 200, "0000");
        assertThat(data(browser.get("/api/v1/auth/me")).get("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    void lastEnabledAdministratorCannotBeDisabledOrDemotedEvenWithConcurrentRequests() throws Exception {
        Account onlyAdmin = account("ADMIN", false);
        Browser first = new Browser();
        assertStatus(first.login(onlyAdmin), 200, "0000");
        assertStatus(first.patch("/api/v1/upms/accounts/" + onlyAdmin.id(), Map.of("displayName", "合成管理員",
                "role", "ADMIN", "enabled", false, "version", 0)), 409, "E0409");
        assertStatus(first.patch("/api/v1/upms/accounts/" + onlyAdmin.id(), Map.of("displayName", "合成管理員",
                "role", "DRIVER", "enabled", true, "version", 0)), 409, "E0409");
        Account other = account("ADMIN", false);
        Browser second = new Browser();
        assertStatus(second.login(other), 200, "0000");
        // Get both tokens before racing the writes so each request carries a valid session-bound token.
        String firstToken = first.csrf();
        String secondToken = second.csrf();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Integer>>of(
                    () -> first.send("PATCH", "/api/v1/upms/accounts/" + other.id(), Map.of("displayName", "合成管理員",
                            "role", "ADMIN", "enabled", false, "version", 0), firstToken).statusCode(),
                    () -> second.send("PATCH", "/api/v1/upms/accounts/" + onlyAdmin.id(), Map.of("displayName", "合成管理員",
                            "role", "ADMIN", "enabled", false, "version", 0), secondToken).statusCode()));
            List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
            assertThat(statuses.stream().filter(value -> value == 200).count()).isEqualTo(1);
            assertThat(statuses).allMatch(value -> value == 200 || value == 401 || value == 409);
        }
        assertThat(count("SELECT count(*) FROM logistics.upms_account WHERE role='ADMIN' AND enabled")).isEqualTo(1);
    }

    private Account account(String role, boolean temporary) throws Exception {
        String username = username();
        String password = UpmsTestData.password();
        try (var connection = dataSource.getConnection()) {
            UUID id = UpmsTestData.insert(connection, encoder, username, role, password, temporary, clock.instant());
            return new Account(id, username, password);
        }
    }

    private String username() {
        return "test-" + UUID.randomUUID().toString().substring(0, 16);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private Map<String, Object> credentials(Account account) {
        return Map.of("username", account.username(), "password", account.password());
    }

    private Map<String, Object> createBody(String role) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("username", username());
        body.put("displayName", "合成新帳號");
        body.put("role", role);
        body.put("password", UpmsTestData.password());
        return body;
    }

    private JsonNode data(HttpResponse<String> response) {
        return json.readTree(response.body()).get("data");
    }

    private void assertStatus(HttpResponse<String> response, int expectedStatus, String expectedCode) {
        assertThat(response.statusCode()).as("HTTP status for UPMS response").isEqualTo(expectedStatus);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(expectedCode);
        assertThat(body.get("timestamp").asString()).endsWith("+08:00");
    }

    private record Account(UUID id, String username, String password) {
    }

    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .sslContext(TlsTestSupport.trustedContext()).connectTimeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, null);
        }

        String csrf() throws Exception {
            HttpResponse<String> response = get("/api/v1/auth/csrf");
            assertStatus(response, 200, "0000");
            return data(response).get("token").asString();
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XKSESSION"))
                    .map(java.net.HttpCookie::getValue).findFirst().orElse("");
        }

        HttpResponse<String> login(Account account) throws Exception {
            return post("/api/v1/auth/login", credentials(account), true);
        }

        HttpResponse<String> post(String path, Object body, boolean includeCsrf) throws Exception {
            return send("POST", path, body, includeCsrf ? csrf() : null);
        }

        HttpResponse<String> patch(String path, Object body) throws Exception {
            return send("PATCH", path, body, csrf());
        }

        HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
            if (token != null) request.header("X-CSRF-TOKEN", token);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
