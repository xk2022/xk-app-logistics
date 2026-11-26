package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.support.MutableTestClock;
import com.xksystem.logistics.support.SyntheticDispatchPolicyConfiguration;
import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.TomContract;
import com.xksystem.logistics.support.UpmsTestData;
import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.upms.UpmsException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** New TMS contract exercised through real TLS, authentication and PostgreSQL transactions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TmsIT.TimeConfiguration.class, SyntheticDispatchPolicyConfiguration.class})
class TmsIT {
    private static final List<String> OWNED_TABLES = List.of("tms_task_dispatch", "tms_assignment",
            "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency");
    private static final List<String> PAGE_FIELDS = List.of("content", "page", "size", "totalElements", "totalPages");
    private static final List<String> TASK_FIELDS = List.of("taskId", "taskNo", "orderId", "orderNo", "orderType",
            "orderVersion", "generation", "taskKind", "pickupName", "deliveryName", "prerequisiteReady",
            "currentAssignmentId", "policyMode", "blockingReasons");
    private static final List<String> MY_FIELDS = List.of("assignmentId", "taskId", "taskNo", "item", "taskKind",
            "pickupName", "pickupAddress", "deliveryName", "deliveryAddress", "siteContact", "plannedStartAt",
            "plannedEndAt", "assignedAt", "policyMode");
    private static final List<String> ASSIGNMENT_FIELDS = List.of("id", "taskId", "orderId", "status", "version",
            "assignedGeneration", "endedGeneration", "driver", "mainVehicle", "trailer", "source", "policyMode",
            "assignedBy", "assignedAt", "assignmentReason", "endedBy", "endedAt", "endReason",
            "previousAssignmentId", "reservations", "events");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres")).withDatabaseName("xk_logistics_e2e");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        TlsTestSupport.register(registry);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableTestClock tmsClock() { return new MutableTestClock(); }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    @Autowired TaskQueries taskSources;
    @Autowired PlatformTransactionManager transactionManager;
    private final JsonMapper json = JsonMapper.builder().build();
    private Account actor;
    private Account firstDriverAccount;
    private Account secondDriverAccount;
    private Browser admin;
    private Browser dispatcher;
    private Browser firstDriverBrowser;
    private Browser secondDriverBrowser;
    private JsonNode firstDriver;
    private JsonNode secondDriver;
    private JsonNode firstMain;
    private JsonNode secondMain;
    private JsonNode firstTrailer;
    private JsonNode secondTrailer;
    private UUID mainType;
    private UUID trailerType;
    private int driverSequence;

    @BeforeEach
    void isolateSyntheticDispatchSourcesAndIndependentNormalSessions() throws Exception {
        jdbc.execute("TRUNCATE TABLE logistics.tms_execution_command,logistics.tms_execution_event,logistics.tms_execution,logistics.tms_idempotency,logistics.tms_dispatch_event,"
                + "logistics.tms_resource_reservation,logistics.tms_assignment,logistics.tms_task_dispatch,"
                + "logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle,"
                + "logistics.order_core,logistics.tom_order,logistics.cargo_item,logistics.transport_task,"
                + "logistics.task_dependency,logistics.order_draft_revision,logistics.api_idempotency,"
                + "logistics.crm_contact,logistics.crm_address,logistics.crm_customer,logistics.adm_change_log,"
                + "logistics.upms_audit,logistics.upms_account");
        jdbc.update("DELETE FROM logistics.adm_dict_item WHERE NOT read_only");
        jdbc.update("UPDATE logistics.adm_dict_category SET enabled=true,version=0 WHERE NOT read_only");
        clock.reset(); driverSequence = 0;
        actor = account("ADMIN", false); admin = browser(actor);
        dispatcher = browser(account("DISPATCHER", false));
        firstDriverAccount = account("DRIVER", false); secondDriverAccount = account("DRIVER", false);
        firstDriverBrowser = browser(firstDriverAccount); secondDriverBrowser = browser(secondDriverAccount);
        mainType = dictionaryItem("VEHICLE_TYPE", "SYNTHETIC_TMS_IT_MAIN", "合成派工主車類別");
        trailerType = dictionaryItem("VEHICLE_TYPE", "SYNTHETIC_TMS_IT_TRAILER", "合成派工拖板類別");
        firstDriver = driver(firstDriverAccount); secondDriver = driver(secondDriverAccount);
        firstMain = vehicle(mainType); secondMain = vehicle(mainType);
        firstTrailer = vehicle(trailerType); secondTrailer = vehicle(trailerType);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMPORT", "EXPORT", "LOCAL"})
    void acceptedOriginalOrderGraphsDispatchOnlyTheirReadyFirstSegmentWithoutRewritingTom(String type) throws Exception {
        JsonNode order = acceptedOrder(type); UUID task = firstTask(order);
        Map<String, String> before = tomDigest();
        JsonNode result = assign(admin, task, 0, firstDriver, firstMain, firstTrailer, key());
        shape(result, List.of("taskId", "generation", "assignment"));
        assertThat(result.get("taskId").asString()).isEqualTo(task.toString());
        assertThat(result.get("generation").asLong()).isEqualTo(1);
        JsonNode assigned = result.get("assignment");
        assignment(assigned, "ASSIGNED", 0, 1, 3);
        assertThat(assigned.get("policyMode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");
        assertThat(assigned.get("assignedBy").asString()).isEqualTo(actor.id().toString());
        assertThat(assigned.get("source").get("orderVersion").asLong()).isEqualTo(order.get("version").asLong());
        assertThat(assigned.get("source").get("orderId").asString()).isEqualTo(id(order));
        assertThat(tomDigest()).isEqualTo(before);
        assertThat(activeReservations()).isEqualTo(3);
        JsonNode detail = detail(admin, task);
        assertThat(detail.get("current")).isEqualTo(assigned);
        assertThat(detail.get("task").get("generation").asLong()).isEqualTo(1);
        assertThat(detail.get("history").get("totalElements").asLong()).isEqualTo(1);
        shape(detail.get("task"), TASK_FIELDS);
    }

    @Test
    void sevenRoutesEnforceNormalRolesCsrfAndExactActorDerivedSelfBoundary() throws Exception {
        JsonNode order = acceptedOrder("LOCAL"); UUID task = firstTask(order);
        JsonNode assigned = assign(admin, task, 0, firstDriver, firstMain, null, key()).get("assignment");
        String assignmentId = id(assigned);
        var reads = List.of("/tms/tasks", "/tms/tasks/" + task + "/dispatch", "/tms/resources?taskId=" + task + "&position=DRIVER");
        Browser anonymous = new Browser();
        for (String path : reads) {
            response(anonymous.get(path), 401, "E0401");
            response(firstDriverBrowser.get(path), 403, "E0403");
            response(dispatcher.get(path), 200, "0000");
        }
        for (var command : List.of(
                new Command("/tms/tasks/" + task + "/dispatch", selection(1, secondDriver, secondMain, null)),
                new Command("/tms/dispatches/" + assignmentId + "/revoke", fields("expectedGeneration", 1, "reason", "合成撤派")),
                new Command("/tms/dispatches/" + assignmentId + "/reassign", replacement(1, secondDriver, secondMain, null, "合成改派")))) {
            response(firstDriverBrowser.write("POST", command.path(), command.body(), key()), 403, "E0403");
            response(anonymous.write("POST", command.path(), command.body(), key()), 401, "E0401");
        }
        response(admin.get("/tms/my-tasks"), 403, "E0403");
        response(dispatcher.get("/tms/my-tasks"), 403, "E0403");
        var csrfRefusal = admin.send("POST", "/tms/dispatches/" + assignmentId + "/revoke",
                json.writeValueAsString(fields("expectedGeneration", 1, "reason", "不得繞過CSRF")), key(), null);
        response(csrfRefusal, 403, "E0403");
        assertThat(data(csrfRefusal).propertyNames()).containsExactly("reason");
        assertThat(data(csrfRefusal).get("reason").asString()).isEqualTo("CSRF_INVALID");
        var forbiddenSelf = admin.get("/tms/my-tasks"); response(forbiddenSelf, 403, "E0403");
        assertThat(data(forbiddenSelf).propertyNames()).containsExactly("reason");
        assertThat(data(forbiddenSelf).get("reason").asString()).isEqualTo("FORBIDDEN");
        Browser temporary = browser(account("DRIVER", true));
        response(temporary.get("/tms/my-tasks"), 403, "E0403");
        JsonNode own = page(firstDriverBrowser.get("/tms/my-tasks")).get(0);
        shape(own, MY_FIELDS);
        assertThat(own.get("assignmentId").asString()).isEqualTo(assignmentId);
        assertThat(own.get("taskId").asString()).isEqualTo(task.toString());
        assertThat(page(secondDriverBrowser.get("/tms/my-tasks"))).isEmpty();
        for (String query : List.of("driverId=" + uuid(secondDriver), "accountId=" + secondDriverAccount.id(),
                "orderId=" + id(order), "q=合成", "page=0&page=1"))
            response(firstDriverBrowser.get("/tms/my-tasks?" + encodeQuery(query)), 400, "E0400");
    }

    @Test
    void strictWriteGrammarAndQueryBoundsRejectBeforeAnyDispatchStateChanges() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); String path = "/tms/tasks/" + task + "/dispatch";
        Map<String, String> before = ownedDigest();
        String valid = json.writeValueAsString(selection(0, firstDriver, firstMain, null));
        for (String payload : List.of("null", "[]", "{}", valid + " {}", valid.substring(0, valid.length() - 1) + ",\"extra\":true}",
                valid.replace("\"expectedGeneration\":0", "\"expectedGeneration\":0,\"expectedGeneration\":0"),
                valid.replace("\"expectedGeneration\":0", "\"expectedGeneration\":0.1"),
                valid.replace("\"expectedGeneration\":0", "\"expectedGeneration\":9007199254740992"))) {
            response(admin.send("POST", path, payload, key(), admin.csrf()), 400, "E0400");
        }
        response(admin.write("POST", path + "?ignored=true", selection(0, firstDriver, firstMain, null), key()), 400, "E0400");
        response(admin.write("POST", path, selection(0, firstDriver, firstMain, firstMain), key()), 400, "E0400");
        response(admin.send("POST", path, valid, null, admin.csrf()), 400, "E0400");
        response(admin.send("POST", path, valid, "invalid key", admin.csrf()), 400, "E0400");
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", admin.csrf()).header("Idempotency-Key", key()).header("Idempotency-Key", key())
                .POST(HttpRequest.BodyPublishers.ofString(valid)).build();
        response(admin.client.send(request, HttpResponse.BodyHandlers.ofString()), 400, "E0400");
        response(admin.send("PUT", path, valid, key(), admin.csrf()), 405, "E0405");
        var nonJson = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "text/plain")
                .header("X-CSRF-TOKEN", admin.csrf()).header("Idempotency-Key", key()).POST(HttpRequest.BodyPublishers.ofString(valid)).build();
        response(admin.client.send(nonJson, HttpResponse.BodyHandlers.ofString()), 415, "E0415");
        for (String query : List.of("page=-1", "page=100001", "size=0", "size=101", "state=PENDING", "page=0&page=1",
                "unknown=value", "orderId=not-a-uuid", "q=" + "x".repeat(101)))
            response(admin.get("/tms/tasks?" + query), 400, "E0400");
        for (String query : List.of("historyPage=-1", "historySize=101", "historyPage=0&historyPage=1", "q=forbidden"))
            response(admin.get(path + "?" + query), 400, "E0400");
        for (String query : List.of("position=DRIVER", "taskId=" + task, "taskId=" + task + "&position=OTHER",
                "taskId=" + task + "&position=MAIN&assignmentId=" + UUID.randomUUID()))
            response(admin.get("/tms/resources?" + query), 400, "E0400");
        assertThat(ownedDigest()).isEqualTo(before);
    }

    @Test
    void acceptedTaskIndexHasHonestBoundedPagesLiteralSearchAndDependencyBlockers() throws Exception {
        JsonNode order = acceptedOrder("IMPORT"); UUID task = firstTask(order);
        JsonNode all = data(ok(admin.get("/tms/tasks?state=ALL&orderId=" + id(order) + "&size=1")));
        shape(all, PAGE_FIELDS); assertThat(all.get("content").size()).isEqualTo(1);
        assertThat(all.get("totalElements").asLong()).isEqualTo(order.get("tasks").size());
        JsonNode last = data(ok(admin.get("/tms/tasks?state=ALL&orderId=" + id(order) + "&size=1&page=100000")));
        assertThat(last.get("content")).isEmpty();
        assertThat(last.get("totalElements")).isEqualTo(all.get("totalElements"));
        assertThat(page(admin.get("/tms/tasks?q=%25"))).isEmpty();
        JsonNode ready = detail(admin, task).get("task");
        assertThat(ready.get("prerequisiteReady").asBoolean()).isTrue();
        UUID dependent = dependentTask(order);
        JsonNode blocked = detail(admin, dependent).get("task");
        assertThat(blocked.get("prerequisiteReady").asBoolean()).isFalse();
        assertThat(blocked.get("blockingReasons").toString()).contains("PREREQUISITE_NOT_READY");
        business(admin.write("POST", "/tms/tasks/" + dependent + "/dispatch", selection(0, firstDriver, firstMain, null), key()), "E1604", "PREREQUISITE_NOT_READY");
        JsonNode assigned = assign(dispatcher, task, 0, firstDriver, firstMain, null, key());
        JsonNode filtered = data(ok(admin.get("/tms/tasks?state=ASSIGNED&orderId=" + id(order))));
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
        assertThat(filtered.get("content").get(0).get("currentAssignmentId")).isEqualTo(assigned.get("assignment").get("id"));
        assertThat(data(ok(admin.get("/tms/tasks?state=UNASSIGNED&orderId=" + id(order)))).get("totalElements").asLong())
                .isEqualTo(order.get("tasks").size() - 1);
    }

    @Test
    void revokeAndReassignAtomicallyReleaseResourcesKeepHistoryAndFollowCurrentDriverBinding() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL"));
        JsonNode first = assign(admin, task, 0, firstDriver, firstMain, firstTrailer, key()).get("assignment");
        JsonNode renamed = patch("/fms/drivers/" + id(firstDriver), firstDriver, fields("name", "合成派工後更名"));
        JsonNode corrected = patch("/fms/vehicles/" + id(firstMain), firstMain, fields("plateNo", plate()));
        assertThat(detail(admin, task).get("current").get("driver").get("name")).isEqualTo(first.get("driver").get("name"));
        assertThat(detail(admin, task).get("current").get("mainVehicle").get("plateNo")).isEqualTo(first.get("mainVehicle").get("plateNo"));
        assertThat(renamed.get("version").asLong()).isEqualTo(1); assertThat(corrected.get("version").asLong()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        JsonNode changed = data(ok(admin.write("POST", "/tms/dispatches/" + id(first) + "/reassign",
                replacement(1, secondDriver, secondMain, secondTrailer, "  合成 e\u0301 改派\n  "), key())));
        assertThat(changed.get("generation").asLong()).isEqualTo(2);
        JsonNode current = changed.get("assignment"); assignment(current, "ASSIGNED", 0, 2, 3);
        assertThat(current.get("previousAssignmentId").asString()).isEqualTo(id(first));
        assertThat(current.get("assignmentReason").asString()).isEqualTo("合成 é 改派");
        JsonNode history = detail(admin, task).get("history");
        assertThat(history.get("totalElements").asLong()).isEqualTo(2);
        JsonNode old = history.get("content").get(1); assignment(old, "SUPERSEDED", 1, 1, 3);
        assertThat(old.get("endedGeneration").asLong()).isEqualTo(2);
        assertThat(old.get("driver")).isEqualTo(first.get("driver"));
        for (JsonNode reservation : old.get("reservations")) assertThat(reservation.get("releasedAt").isNull()).isFalse();
        assertThat(activeReservations()).isEqualTo(3);
        assertThat(page(firstDriverBrowser.get("/tms/my-tasks"))).isEmpty();
        assertThat(page(secondDriverBrowser.get("/tms/my-tasks"))).hasSize(1);
        clock.advance(Duration.ofSeconds(1));
        JsonNode revoked = data(ok(dispatcher.write("POST", "/tms/dispatches/" + id(current) + "/revoke",
                fields("expectedGeneration", 2, "reason", " 合成撤派 "), key())));
        assignment(revoked.get("assignment"), "REVOKED", 1, 2, 3);
        assertThat(revoked.get("generation").asLong()).isEqualTo(3);
        assertThat(activeReservations()).isZero();
        assertThat(detail(admin, task).get("current").isNull()).isTrue();
        assertThat(page(secondDriverBrowser.get("/tms/my-tasks"))).isEmpty();
        JsonNode again = assign(admin, task, 3, firstDriver, firstMain, null, key());
        assertThat(again.get("generation").asLong()).isEqualTo(4);
        assertThat(detail(admin, task).get("history").get("totalElements").asLong()).isEqualTo(3);
    }

    // Concurrency, replay, source races and fault injection are kept below the ordinary HTTP scenarios.

    @ParameterizedTest
    @ValueSource(strings = {"TASK", "DRIVER", "MAIN", "TRAILER", "CROSS_POSITION"})
    void competingAssignmentsNeverShareTaskDriverOrVehicleAcrossPositions(String collision) throws Exception {
        UUID firstTask = firstTask(acceptedOrder("LOCAL"));
        UUID otherTask = firstTask(acceptedOrder("LOCAL"));
        JsonNode firstVehicle = firstMain;
        JsonNode nextDriver = secondDriver, nextMain = secondMain, nextTrailer = secondTrailer;
        if (collision.equals("TASK")) otherTask = firstTask;
        if (collision.equals("DRIVER")) nextDriver = firstDriver;
        if (collision.equals("MAIN")) nextMain = firstMain;
        if (collision.equals("TRAILER")) nextTrailer = firstTrailer;
        if (collision.equals("CROSS_POSITION")) {
            firstVehicle = vehicle(dictionaryItem("VEHICLE_TYPE", "SYNTHETIC_TMS_IT_SHARED", "合成跨位置排他測試類別"));
            nextTrailer = firstVehicle;
        }
        String firstPath = "/tms/tasks/" + firstTask + "/dispatch", secondPath = "/tms/tasks/" + otherTask + "/dispatch";
        String firstBody = json.writeValueAsString(selection(0, firstDriver, firstVehicle, firstTrailer));
        String secondBody = json.writeValueAsString(selection(0, nextDriver, nextMain, nextTrailer));
        String tokenA = admin.csrf(), tokenB = dispatcher.csrf();
        var pool = Executors.newFixedThreadPool(2); pause("tms_assignment", "BEFORE INSERT");
        try (var gate = dataSource.getConnection()) {
            advisory(gate, true);
            try {
                var first = pool.submit(() -> admin.send("POST", firstPath, firstBody, key(), tokenA));
                awaitLock("tms_assignment");
                var later = pool.submit(() -> dispatcher.send("POST", secondPath, secondBody, key(), tokenB));
                awaitLock("upms_lock"); assertThat(later.isDone()).isFalse();
                advisory(gate, false); ok(first.get(15, TimeUnit.SECONDS));
                business(later.get(15, TimeUnit.SECONDS), collision.equals("TASK") ? "E1601" : "E1602",
                        collision.equals("TASK") ? "VERSION_CONFLICT" : "RESOURCE_OCCUPIED");
            } finally { advisory(gate, false); }
        } finally { unpause("tms_assignment"); pool.shutdownNow(); }
        assertThat(count("tms_assignment")).isEqualTo(1);
        assertThat(count("tms_dispatch_event")).isEqualTo(1);
        assertThat(count("tms_idempotency")).isEqualTo(1);
        assertThat(activeReservations()).isEqualTo(3);
    }

    @Test
    void inProgressSameKeyCannotQueueBehindCanonicalOrCreateAnotherResult() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); String path = "/tms/tasks/" + task + "/dispatch";
        String payload = json.writeValueAsString(selection(0, firstDriver, firstMain, null));
        String requestKey = key(), token = admin.csrf();
        pause("tms_assignment", "BEFORE INSERT"); var pool = Executors.newFixedThreadPool(2);
        try (var gate = dataSource.getConnection()) {
            advisory(gate, true);
            try {
                var first = pool.submit(() -> admin.send("POST", path, payload, requestKey, token));
                awaitLock("tms_assignment");
                var repeated = pool.submit(() -> admin.send("POST", path, payload, requestKey, token));
                business(repeated.get(5, TimeUnit.SECONDS), "E1608", "IDEMPOTENCY_IN_PROGRESS");
                advisory(gate, false); ok(first.get(15, TimeUnit.SECONDS));
            } finally { advisory(gate, false); }
        } finally { unpause("tms_assignment"); pool.shutdownNow(); }
        assertThat(count("tms_assignment")).isEqualTo(1); assertThat(count("tms_dispatch_event")).isEqualTo(1);
        assertThat(count("tms_idempotency")).isEqualTo(1); assertThat(activeReservations()).isEqualTo(2);
    }

    @Test
    void allThreeCommandReplaysKeepHistoricalDataWithoutReoccupyingOrAddingEvents() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL"));
        String assignPath = "/tms/tasks/" + task + "/dispatch", assignKey = key();
        Map<String, Object> assignBody = selection(0, firstDriver, firstMain, firstTrailer);
        JsonNode assigned = data(ok(admin.write("POST", assignPath, assignBody, assignKey)));
        String reassignPath = "/tms/dispatches/" + id(assigned.get("assignment")) + "/reassign", reassignKey = key();
        Map<String, Object> reassignBody = replacement(1, secondDriver, secondMain, secondTrailer, "合成保留原次改派");
        JsonNode changed = data(ok(admin.write("POST", reassignPath, reassignBody, reassignKey)));
        String revokePath = "/tms/dispatches/" + id(changed.get("assignment")) + "/revoke", revokeKey = key();
        Map<String, Object> revokeBody = fields("expectedGeneration", 2, "reason", "合成保留原次撤派");
        JsonNode revoked = data(ok(admin.write("POST", revokePath, revokeBody, revokeKey)));
        Map<String, String> before = ownedDigest();
        for (Replay replay : List.of(new Replay(assignPath, assignBody, assignKey, assigned),
                new Replay(reassignPath, reassignBody, reassignKey, changed), new Replay(revokePath, revokeBody, revokeKey, revoked))) {
            String numericEquivalent = json.writeValueAsString(replay.body()).replace("\"expectedGeneration\":0", "\"expectedGeneration\":0e0")
                    .replace("\"expectedGeneration\":1", "\"expectedGeneration\":1.0").replace("\"expectedGeneration\":2", "\"expectedGeneration\":2e0");
            var reply = admin.send("POST", replay.path(), numericEquivalent, replay.key(), admin.csrf());
            ok(reply); assertThat(reply.headers().firstValue("Idempotency-Replayed")).contains("true");
            assertThat(data(reply)).isEqualTo(replay.result());
        }
        assertThat(ownedDigest()).isEqualTo(before); assertThat(activeReservations()).isZero();
        assertThat(assigned.get("assignment").get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(changed.get("assignment").get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(revoked.get("assignment").get("status").asString()).isEqualTo("REVOKED");
        var different = selection(3, secondDriver, secondMain, null);
        business(admin.write("POST", assignPath, different, assignKey), "E1607", "IDEMPOTENCY_KEY_REUSED");
        // Actor/path scope is independent: the other manager's same key is a new legitimate command.
        JsonNode independent = assign(dispatcher, task, 3, firstDriver, firstMain, null, assignKey);
        assertThat(independent.get("generation").asLong()).isEqualTo(4);
        assertThat(count("tms_idempotency")).isEqualTo(4);
    }

    @Test
    void historicalReplayAndPredepartureRevocationDoNotRequireTodaysAvailabilityPolicyOrOpenSource() throws Exception {
        JsonNode order = acceptedOrder("LOCAL"); UUID task = firstTask(order); String requestKey = key();
        JsonNode assigned = assign(admin, task, 0, firstDriver, firstMain, firstTrailer, requestKey);
        // Simulate externally committed source changes; ordinary FMS API guards are verified separately.
        jdbc.update("UPDATE logistics.fms_driver SET employment_status='INACTIVE',duty_status='OFF_DUTY',version=version+1 WHERE id=?", uuid(firstDriver));
        jdbc.update("UPDATE logistics.fms_vehicle SET administrative_status='MAINTENANCE',version=version+1 WHERE id=?", uuid(firstMain));
        jdbc.update("UPDATE logistics.order_core SET lifecycle_status='CLOSED' WHERE id=?", uuid(order));
        Map<String, String> before = ownedDigest();
        var replay = admin.write("POST", "/tms/tasks/" + task + "/dispatch", selection(0, firstDriver, firstMain, firstTrailer), requestKey);
        ok(replay); assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(data(replay)).isEqualTo(assigned); assertThat(ownedDigest()).isEqualTo(before);
        JsonNode revoked = data(ok(admin.write("POST", "/tms/dispatches/" + id(assigned.get("assignment")) + "/revoke",
                fields("expectedGeneration", 1, "reason", "合成來源變更後撤派"), key())));
        assertThat(revoked.get("generation").asLong()).isEqualTo(2); assertThat(revoked.get("assignment").get("status").asString()).isEqualTo("REVOKED");
        assertThat(activeReservations()).isZero();
    }

    @Test
    void generationConflictsEndedTargetsAndNoopNeverChangeTheCurrentAllocation() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL"));
        JsonNode first = assign(admin, task, 0, firstDriver, firstMain, null, key()).get("assignment");
        Map<String, String> before = ownedDigest();
        var stale = admin.write("POST", "/tms/dispatches/" + id(first) + "/revoke", fields("expectedGeneration", 0, "reason", "合成過期版本"), key());
        business(stale, "E1601", "VERSION_CONFLICT"); assertThat(data(stale).get("actualGeneration").asLong()).isEqualTo(1);
        response(admin.write("POST", "/tms/dispatches/" + id(first) + "/reassign", replacement(1, firstDriver, firstMain, null, "同組不得虛增"), key()), 400, "E0400");
        for (String reason : List.of("", " \u00a0 ", "x".repeat(501), "control\u0000value"))
            response(admin.write("POST", "/tms/dispatches/" + id(first) + "/revoke", fields("expectedGeneration", 1, "reason", reason), key()), 400, "E0400");
        assertThat(ownedDigest()).isEqualTo(before);
        JsonNode next = data(ok(admin.write("POST", "/tms/dispatches/" + id(first) + "/reassign", replacement(1, secondDriver, secondMain, null, "合成新派工"), key())));
        before = ownedDigest();
        business(admin.write("POST", "/tms/dispatches/" + id(first) + "/revoke", fields("expectedGeneration", 2, "reason", "不能撤舊歷史"), key()), "E1603", "INVALID_STATE");
        assertThat(ownedDigest()).isEqualTo(before);
        assertThat(detail(admin, task).get("current")).isEqualTo(next.get("assignment"));
    }

    @Test
    void elapsedTimeAndRevocationNeverPretendThatAPredecessorCompleted() throws Exception {
        JsonNode order = acceptedOrder("LOCAL"); UUID first = firstTask(order), dependent = dependentTask(order);
        JsonNode assignment = assign(admin, first, 0, firstDriver, firstMain, null, key()).get("assignment");
        clock.advance(Duration.ofDays(365));
        // The original session expires as well; a fresh normal session still gets no completion grant.
        admin = browser(actor);
        ok(admin.write("POST", "/tms/dispatches/" + id(assignment) + "/revoke", fields("expectedGeneration", 1, "reason", "合成取消首段"), key()));
        business(admin.write("POST", "/tms/tasks/" + dependent + "/dispatch", selection(0, secondDriver, secondMain, null), key()), "E1604", "PREREQUISITE_NOT_READY");
        assertThat(detail(admin, dependent).get("task").get("prerequisiteReady").asBoolean()).isFalse();
        assertThat(activeReservations()).isZero();
    }

    @Test
    void unavailableSourcesRejectAtomicallyAndSameFailedKeyMayBeRetriedAfterCorrection() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); String requestKey = key();
        JsonNode disabled = patch("/fms/vehicles/" + id(firstMain), firstMain, fields("administrativeStatus", "INACTIVE"));
        Map<String, String> before = ownedDigest();
        business(admin.write("POST", "/tms/tasks/" + task + "/dispatch", selection(0, firstDriver, firstMain, null), requestKey), "E1606", "MASTER_UNAVAILABLE");
        assertThat(ownedDigest()).isEqualTo(before);
        patch("/fms/vehicles/" + id(firstMain), disabled, fields("administrativeStatus", "AVAILABLE"));
        assertThat(assign(admin, task, 0, firstDriver, firstMain, null, requestKey).get("generation").asLong()).isEqualTo(1);
    }

    @Test
    void defaultUnconfirmedMasterCombinationsCannotBecomeSyntheticApprovalThroughAdministrativeAvailability() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL"));
        JsonNode ordinary = data(created(admin.write("POST", "/fms/drivers", fields("driverCode", "ORDINARY_SYNTHETIC_DRIVER", "name", "未核准類別合成司機", "dutyStatus", "ON_DUTY"), null)));
        Map<String, String> before = ownedDigest();
        business(admin.write("POST", "/tms/tasks/" + task + "/dispatch", selection(0, ordinary, firstMain, null), key()), "E1605", "POLICY_NOT_CONFIRMED");
        assertThat(ownedDigest()).isEqualTo(before);
        JsonNode candidates = data(ok(admin.get("/tms/resources?taskId=" + task + "&position=DRIVER&size=100")));
        JsonNode candidate = null;
        for (JsonNode value : candidates.get("content")) if (value.get("id").asString().equals(id(ordinary))) candidate = value;
        assertThat(candidate).isNotNull();
        assertThat(candidate.get("administrativelyAvailable").asBoolean()).isTrue();
        assertThat(candidate.get("policyApproved").asBoolean()).isFalse();
        assertThat(candidate.get("blockingReasons").toString()).contains("POLICY_NOT_CONFIRMED");
    }

    @Test
    void resourcePagesShowOccupiedSourcesAndOnlyAValidatedCurrentAssignmentCanReuseItsOwnReservation() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); UUID other = firstTask(acceptedOrder("LOCAL"));
        JsonNode current = assign(admin, task, 0, firstDriver, firstMain, firstTrailer, key()).get("assignment");
        String path = "/tms/resources?taskId=" + other + "&position=MAIN&q=" + URLEncoder.encode(firstMain.get("plateNo").asString(), StandardCharsets.UTF_8);
        JsonNode occupied = page(admin.get(path)).get(0);
        assertThat(occupied.get("occupied").asBoolean()).isTrue();
        assertThat(occupied.get("occupation").get("assignmentId").asString()).isEqualTo(id(current));
        assertThat(occupied.get("blockingReasons").toString()).contains("RESOURCE_OCCUPIED");
        JsonNode reusable = page(admin.get(path.replace(other.toString(), task.toString()) + "&assignmentId=" + id(current))).get(0);
        assertThat(reusable.get("blockingReasons").toString()).doesNotContain("RESOURCE_OCCUPIED");
        response(admin.get(path + "&assignmentId=" + id(current)), 400, "E0400");
        JsonNode terminal = data(ok(admin.write("POST", "/tms/dispatches/" + id(current) + "/revoke", fields("expectedGeneration", 1, "reason", "合成撤派"), key()))).get("assignment");
        assertThat(terminal.get("status").asString()).isEqualTo("REVOKED");
        response(admin.get(path.replace(other.toString(), task.toString()) + "&assignmentId=" + id(current)), 400, "E0400");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "MAIN", "TRAILER"})
    void administrativeSourceChangeWinningCanonicalLockPreventsTheQueuedDispatch(String position) throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL"));
        JsonNode source = position.equals("DRIVER") ? firstDriver : position.equals("MAIN") ? firstMain : firstTrailer;
        String sourcePath = (position.equals("DRIVER") ? "/fms/drivers/" : "/fms/vehicles/") + id(source);
        String table = position.equals("DRIVER") ? "fms_driver" : "fms_vehicle";
        Map<String, Object> changes = fields("version", 0, position.equals("DRIVER") ? "dutyStatus" : "administrativeStatus", position.equals("DRIVER") ? "OFF_DUTY" : "INACTIVE");
        String sourceBody = json.writeValueAsString(changes), dispatchBody = json.writeValueAsString(selection(0, firstDriver, firstMain, firstTrailer));
        String tokenA = admin.csrf(), tokenB = dispatcher.csrf();
        pause(table, "BEFORE UPDATE"); var pool = Executors.newFixedThreadPool(2);
        try (var gate = dataSource.getConnection()) {
            advisory(gate, true);
            try {
                var changing = pool.submit(() -> admin.send("PATCH", sourcePath, sourceBody, null, tokenA));
                awaitLock(table);
                var dispatching = pool.submit(() -> dispatcher.send("POST", "/tms/tasks/" + task + "/dispatch", dispatchBody, key(), tokenB));
                awaitLock("upms_lock"); assertThat(dispatching.isDone()).isFalse();
                advisory(gate, false); ok(changing.get(15, TimeUnit.SECONDS));
                business(dispatching.get(15, TimeUnit.SECONDS), "E1606", "MASTER_UNAVAILABLE");
            } finally { advisory(gate, false); }
        } finally { unpause(table); pool.shutdownNow(); }
        assertThat(count("tms_assignment")).isZero(); assertThat(activeReservations()).isZero();
        assertThat(count("tms_dispatch_event")).isZero(); assertThat(count("tms_idempotency")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "MAIN", "TRAILER"})
    void dispatchWinningCanonicalLockKeepsItsSourcesAvailableAndRejectsTheQueuedDisqualification(String position) throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL"));
        JsonNode source = position.equals("DRIVER") ? firstDriver : position.equals("MAIN") ? firstMain : firstTrailer;
        String sourcePath = (position.equals("DRIVER") ? "/fms/drivers/" : "/fms/vehicles/") + id(source);
        String dispatchBody = json.writeValueAsString(selection(0, firstDriver, firstMain, firstTrailer));
        String sourceBody = json.writeValueAsString(fields("version", 0, position.equals("DRIVER") ? "dutyStatus" : "administrativeStatus", position.equals("DRIVER") ? "OFF_DUTY" : "INACTIVE"));
        String tokenA = admin.csrf(), tokenB = dispatcher.csrf();
        pause("tms_assignment", "BEFORE INSERT"); var pool = Executors.newFixedThreadPool(2);
        try (var gate = dataSource.getConnection()) {
            advisory(gate, true);
            try {
                var dispatching = pool.submit(() -> admin.send("POST", "/tms/tasks/" + task + "/dispatch", dispatchBody, key(), tokenA));
                awaitLock("tms_assignment");
                var changing = pool.submit(() -> dispatcher.send("PATCH", sourcePath, sourceBody, null, tokenB));
                awaitLock("upms_lock"); assertThat(changing.isDone()).isFalse();
                advisory(gate, false); ok(dispatching.get(15, TimeUnit.SECONDS));
                response(changing.get(15, TimeUnit.SECONDS), 409, "E0409");
            } finally { advisory(gate, false); }
        } finally { unpause("tms_assignment"); pool.shutdownNow(); }
        JsonNode retained = data(ok(admin.get(sourcePath)));
        assertThat(retained.get("version").asLong()).isZero();
        assertThat(retained.get(position.equals("DRIVER") ? "dutyStatus" : "administrativeStatus").asString())
                .isEqualTo(position.equals("DRIVER") ? "ON_DUTY" : "AVAILABLE");
        assertThat(activeReservations()).isEqualTo(3);
    }

    @Test
    void occupiedQualificationFieldsAreProtectedWhileUnchangedValuesRetainOriginalFmsSemantics() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); assign(admin, task, 0, firstDriver, firstMain, firstTrailer, key());
        UUID otherLicense = dictionaryItem("DRIVER_LICENSE_TYPE", "SYNTHETIC_TMS_IT_LICENSE", "合成不得變更證照類別");
        for (Map<String, Object> change : List.of(fields("employmentStatus", "LEFT"), fields("dutyStatus", "OFF_DUTY"),
                fields("licenseType", otherLicense.toString()), fields("licenseDueDate", "2027-01-01"))) {
            change.put("version", 0); response(admin.write("PATCH", "/fms/drivers/" + id(firstDriver), change, null), 409, "E0409");
        }
        for (Map<String, Object> change : List.of(fields("administrativeStatus", "INACTIVE"), fields("capacityTon", "99"),
                fields("vehicleType", trailerType.toString()), fields("inspectionDueDate", "2027-01-01"))) {
            change.put("version", 0); response(admin.write("PATCH", "/fms/vehicles/" + id(firstMain), change, null), 409, "E0409");
        }
        JsonNode noOp = patch("/fms/vehicles/" + id(firstMain), firstMain, fields("capacityTon", "12.3400", "administrativeStatus", "AVAILABLE"));
        assertThat(noOp.get("version").asLong()).isEqualTo(1);
        assertThat(detail(admin, task).get("current").get("mainVehicle").get("version").asLong()).isZero();
        assertThat(activeReservations()).isEqualTo(3);
    }

    @Test
    void freshBindingNotCopiedAccountIdentityControlsCurrentSelfVisibility() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); JsonNode current = assign(admin, task, 0, firstDriver, firstMain, null, key()).get("assignment");
        JsonNode own = page(firstDriverBrowser.get("/tms/my-tasks")).get(0); shape(own, MY_FIELDS);
        assertThat(own.toString()).doesNotContain("不得出現在司機摘要的整單備註", "customerId", "customerName", "licenseReference", "accountId", "plateNo", "reservations", "generation");
        JsonNode unbound = patch("/fms/drivers/" + id(firstDriver), firstDriver, fields("accountId", null));
        response(firstDriverBrowser.get("/tms/my-tasks"), 404, "E0404");
        Account replacement = account("DRIVER", false); Browser replacementBrowser = browser(replacement);
        patch("/fms/drivers/" + id(firstDriver), unbound, fields("accountId", replacement.id().toString()));
        assertThat(page(replacementBrowser.get("/tms/my-tasks")).get(0).get("assignmentId").asString()).isEqualTo(id(current));
        response(firstDriverBrowser.get("/tms/my-tasks"), 404, "E0404");
        assertThat(activeReservations()).isEqualTo(2);
        assertThat(detail(admin, task).get("current")).isEqualTo(current);
        ok(admin.write("PATCH", "/upms/accounts/" + replacement.id(), accountChange(replacement, "DRIVER", false), null));
        response(replacementBrowser.get("/tms/my-tasks"), 401, "E0401");
    }

    @Test
    void bindingChangeAndCurrentSelfReadSerializeInBothDirections() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); assign(admin, task, 0, firstDriver, firstMain, null, key());
        try (var pool = Executors.newSingleThreadExecutor(); var held = dataSource.getConnection()) {
            held.setAutoCommit(false); canonical(held);
            try (var statement = held.prepareStatement("UPDATE logistics.fms_driver SET account_id=NULL,version=version+1 WHERE id=?")) {
                statement.setObject(1, uuid(firstDriver)); statement.executeUpdate();
            }
            var reading = pool.submit(() -> firstDriverBrowser.get("/tms/my-tasks"));
            awaitLock("upms_lock"); held.commit(); response(reading.get(15, TimeUnit.SECONDS), 404, "E0404");
        }
        JsonNode latest = data(ok(admin.get("/fms/drivers/" + id(firstDriver))));
        latest = patch("/fms/drivers/" + id(firstDriver), latest, fields("accountId", firstDriverAccount.id().toString()));
        String token = admin.csrf(), unbind = json.writeValueAsString(fields("version", latest.get("version").asLong(), "accountId", null));
        try (var pool = Executors.newFixedThreadPool(2); var source = dataSource.getConnection()) {
            source.setAutoCommit(false);
            try (var statement = source.prepareStatement("SELECT id FROM logistics.fms_driver WHERE id=? FOR UPDATE")) { statement.setObject(1, uuid(firstDriver)); statement.executeQuery().close(); }
            var reading = pool.submit(() -> firstDriverBrowser.get("/tms/my-tasks")); awaitLock("fms_driver");
            var changing = pool.submit(() -> admin.send("PATCH", "/fms/drivers/" + id(firstDriver), unbind, null, token));
            awaitLock("upms_lock"); assertThat(changing.isDone()).isFalse(); source.commit();
            assertThat(page(reading.get(15, TimeUnit.SECONDS))).hasSize(1); ok(changing.get(15, TimeUnit.SECONDS));
            response(firstDriverBrowser.get("/tms/my-tasks"), 404, "E0404");
        }
    }

    @Test
    void queuedManagerRevocationAndHistoricalReplayBothRequireFreshAuthorization() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); String path = "/tms/tasks/" + task + "/dispatch", requestKey = key();
        Account manager = account("DISPATCHER", false); Browser browser = browser(manager);
        String token = browser.csrf(), payload = json.writeValueAsString(selection(0, firstDriver, firstMain, null));
        Map<String, String> before = ownedDigest();
        try (var pool = Executors.newSingleThreadExecutor(); var held = dataSource.getConnection()) {
            held.setAutoCommit(false); canonical(held);
            var pending = pool.submit(() -> browser.send("POST", path, payload, requestKey, token)); awaitLock("upms_lock");
            try (var statement = held.prepareStatement("UPDATE logistics.upms_account SET auth_version=auth_version+1,role='DRIVER' WHERE id=?")) { statement.setObject(1, manager.id()); statement.executeUpdate(); }
            held.commit(); response(pending.get(15, TimeUnit.SECONDS), 401, "E0401");
        }
        assertThat(ownedDigest()).isEqualTo(before);
        String successfulKey = key(); assign(admin, task, 0, firstDriver, firstMain, null, successfulKey); before = ownedDigest();
        String successfulBody = json.writeValueAsString(selection(0, firstDriver, firstMain, null)), oldToken = admin.csrf();
        jdbc.update("UPDATE logistics.upms_account SET auth_version=auth_version+1 WHERE id=?", actor.id());
        var replay = admin.send("POST", path, successfulBody, successfulKey, oldToken);
        assertThat(replay.statusCode()).isIn(401, 403); assertThat(replay.headers().firstValue("Idempotency-Replayed").map("true"::equals).orElse(false)).isFalse();
        assertThat(ownedDigest()).isEqualTo(before);
    }

    @Test
    void taskSourcePortIsBoundedImmutableNarrowAndRequiresWritableCallerLocks() throws Exception {
        JsonNode order = acceptedOrder("LOCAL"); UUID task = firstTask(order), dependent = dependentTask(order);
        assertThat(taskSources.bulk(List.of())).isEmpty();
        assertThat(taskSources.find(null)).isEmpty(); assertThat(taskSources.find(UUID.randomUUID())).isEmpty();
        assertThat(taskSources.bulk(java.util.Collections.nCopies(100, task))).containsOnlyKeys(task);
        assertThatThrownBy(() -> taskSources.bulk(java.util.Collections.nCopies(101, task))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskSources.bulk(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskSources.bulk(java.util.Arrays.asList(task, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskSources.bulk(List.of(task)).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> taskSources.lockCurrentForDispatch(task)).isInstanceOf(IllegalTransactionStateException.class);
        var readOnly = new TransactionTemplate(transactionManager); readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(status -> taskSources.lockCurrentForDispatch(task))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> taskSources.lockCurrentForDispatch(UUID.randomUUID())))
                .isInstanceOfSatisfying(UpmsException.class, failure -> assertThat(failure.status()).isEqualTo(404));
        TaskQueries.Current selected = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForObject("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE", Integer.class);
            return taskSources.lockCurrentForDispatch(task);
        });
        assertThat(selected.accepted()).isTrue(); assertThat(selected.prerequisiteReady()).isTrue();
        assertThat(selected.source().pickupAddress()).isEqualTo(" 合成\u0000原始地址 e\u0301 ");
        assertThat(selected.source().item().grossWeight()).isEqualTo("0.0000000000000000001");
        assertThat(selected.source().plannedStartAt()).isEqualTo("1500-01-02T00:00:00.123456+23:59");
        assertThat(selected.source().siteContact()).isNotNull(); assertThat(selected.source().siteContact().name()).isNull();
        assertThat(taskSources.find(dependent).orElseThrow().source().siteContact()).isNull();
        assertThat(taskSources.find(dependent).orElseThrow().prerequisiteReady()).isFalse();
        jdbc.update("UPDATE logistics.order_core SET company_scope='FOREIGN_SYNTHETIC_SCOPE' WHERE id=?", UUID.fromString(id(order)));
        assertThat(taskSources.find(task)).isEmpty(); assertThat(taskSources.bulk(List.of(task, dependent))).isEmpty();
    }

    @Test
    void draftMissingAndIneligibleSourceCannotCreateAnAssignmentOrAcceptedIdentityIndex() throws Exception {
        JsonNode draft = draftOrder("LOCAL"); UUID task = firstTask(draft);
        assertThat(count("tms_task_dispatch")).isZero(); Map<String, String> before = ownedDigest();
        business(admin.write("POST", "/tms/tasks/" + task + "/dispatch", selection(0, firstDriver, firstMain, null), key()), "E1603", "INVALID_STATE");
        response(admin.write("POST", "/tms/tasks/" + UUID.randomUUID() + "/dispatch", selection(0, firstDriver, firstMain, null), key()), 404, "E0404");
        assertThat(ownedDigest()).isEqualTo(before);
    }

    @Test
    void acceptedIdentitySinkFailureRollsBackTheOriginalAcceptanceAndItsSuccessfulReplayDoesNotReseed() throws Exception {
        JsonNode draft = draftOrder("LOCAL"); String path = "/orders/" + id(draft) + "/accept", requestKey = key();
        Map<String, String> sourceBefore = tomDigest(), ownedBefore = ownedDigest();
        failStorage("tms_task_dispatch", "BEFORE INSERT", false);
        try {
            var failed = admin.write("POST", path, fields("expectedVersion", 0), requestKey);
            assertThat(failed.statusCode()).isEqualTo(500); assertThat(body(failed).get("code").asString()).isEqualTo("INTERNAL_ERROR");
            TomContract.assertSchema("ErrorEnvelope", body(failed));
            assertThat(failed.body()).doesNotContain("synthetic TMS storage failure", "INSERT INTO", "SQLException");
        } finally { dropFailure("tms_task_dispatch"); }
        assertThat(tomDigest()).isEqualTo(sourceBefore); assertThat(ownedDigest()).isEqualTo(ownedBefore);
        var accepted = admin.write("POST", path, fields("expectedVersion", 0), requestKey); tom(accepted, 200);
        assertThat(count("tms_task_dispatch")).isEqualTo(draft.get("tasks").size());
        sourceBefore = tomDigest(); ownedBefore = ownedDigest();
        var replay = admin.write("POST", path, fields("expectedVersion", 0), requestKey); tom(replay, 200);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(data(replay)).isEqualTo(data(accepted));
        assertThat(tomDigest()).isEqualTo(sourceBefore); assertThat(ownedDigest()).isEqualTo(ownedBefore);
    }

    @Test
    void everyAssignmentEventReservationAndReplayFailureRollsBackTheWholeCommandAndAllowsSameKeyRetry() throws Exception {
        for (String operation : List.of("ASSIGN", "REVOKE", "REASSIGN")) {
            List<String> failureTables = List.of("tms_task_dispatch", "tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency");
            for (String table : failureTables) {
                UUID task = firstTask(acceptedOrder("LOCAL"));
                JsonNode baseline = null;
                if (!operation.equals("ASSIGN")) baseline = assign(admin, task, 0, firstDriver, firstMain, firstTrailer, key()).get("assignment");
                Command command = operation.equals("ASSIGN")
                        ? new Command("/tms/tasks/" + task + "/dispatch", selection(0, firstDriver, firstMain, firstTrailer))
                        : operation.equals("REVOKE") ? new Command("/tms/dispatches/" + id(baseline) + "/revoke", fields("expectedGeneration", 1, "reason", "合成回滾撤派"))
                        : new Command("/tms/dispatches/" + id(baseline) + "/reassign", replacement(1, secondDriver, secondMain, secondTrailer, "合成回滾改派"));
                String requestKey = key(); Map<String, String> ownedBefore = ownedDigest(), sourceBefore = tomDigest();
                JsonNode detailBefore = detail(admin, task);
                String event = (table.equals("tms_task_dispatch") || !operation.equals("ASSIGN") && (table.equals("tms_assignment") || table.equals("tms_resource_reservation"))) ? "BEFORE UPDATE" : "BEFORE INSERT";
                failStorage(table, event, false);
                try {
                    var failed = admin.write("POST", command.path(), command.body(), requestKey);
                    response(failed, 500, "E0500");
                    assertThat(failed.body()).doesNotContain("synthetic TMS storage failure", "INSERT INTO", "UPDATE logistics", "SQLException", "Hibernate", "不得出現在司機摘要");
                } finally { dropFailure(table); }
                assertThat(ownedDigest()).isEqualTo(ownedBefore); assertThat(tomDigest()).isEqualTo(sourceBefore);
                assertThat(detail(admin, task)).isEqualTo(detailBefore);
                ok(admin.write("POST", command.path(), command.body(), requestKey));
                if (operation.equals("ASSIGN") || operation.equals("REASSIGN")) {
                    JsonNode current = detail(admin, task).get("current");
                    ok(admin.write("POST", "/tms/dispatches/" + id(current) + "/revoke",
                            fields("expectedGeneration", operation.equals("ASSIGN") ? 1 : 2, "reason", "合成迴圈釋放"), key()));
                }
                assertThat(activeReservations()).isZero();
            }
        }
    }

    @Test
    void reassignAcquisitionFailureRestoresReleasedOldReservationsAndTerminalTransition() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); JsonNode original = assign(admin, task, 0, firstDriver, firstMain, firstTrailer, key()).get("assignment");
        String path = "/tms/dispatches/" + id(original) + "/reassign", requestKey = key();
        Map<String, Object> command = replacement(1, secondDriver, secondMain, secondTrailer, "合成新占用故障");
        Map<String, String> before = ownedDigest();
        // Reassignment must have released its original rows before trying the new insert.
        failStorage("tms_resource_reservation", "BEFORE INSERT", false);
        try { response(admin.write("POST", path, command, requestKey), 500, "E0500"); }
        finally { dropFailure("tms_resource_reservation"); }
        assertThat(ownedDigest()).isEqualTo(before); assertThat(detail(admin, task).get("current")).isEqualTo(original);
        assertThat(activeReservations()).isEqualTo(3);
        ok(admin.write("POST", path, command, requestKey));
        assertThat(activeReservations()).isEqualTo(3);
    }

    @Test
    void realForeignKeyFailureUsesProtectedRelationAndRollsBackGenerationAssignmentReservationsEventAndReplay() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); String path = "/tms/tasks/" + task + "/dispatch", requestKey = key();
        Map<String, String> before = ownedDigest();
        failStorage("tms_dispatch_event", "BEFORE INSERT", true);
        try { business(admin.write("POST", path, selection(0, firstDriver, firstMain, null), requestKey), "E1609", "PROTECTED_RELATION"); }
        finally { dropFailure("tms_dispatch_event"); }
        assertThat(ownedDigest()).isEqualTo(before); assertThat(activeReservations()).isZero();
        assign(admin, task, 0, firstDriver, firstMain, null, requestKey);
    }

    @Test
    void taskGenerationMaximumCannotOverflowAndSourceVersionOrStateCannotBeSilentlyResnapshotted() throws Exception {
        JsonNode maxOrder = acceptedOrder("LOCAL"); UUID maxTask = firstTask(maxOrder);
        jdbc.update("UPDATE logistics.tms_task_dispatch SET generation=9007199254740991 WHERE task_id=?", maxTask);
        Map<String, String> before = ownedDigest();
        business(admin.write("POST", "/tms/tasks/" + maxTask + "/dispatch", selection(9007199254740991L, firstDriver, firstMain, null), key()), "E1603", "INVALID_STATE");
        assertThat(ownedDigest()).isEqualTo(before);
        JsonNode altered = acceptedOrder("LOCAL"); UUID alteredTask = firstTask(altered);
        jdbc.update("UPDATE logistics.tom_order SET version=version+1 WHERE order_id=?", uuid(altered)); before = ownedDigest();
        business(admin.write("POST", "/tms/tasks/" + alteredTask + "/dispatch", selection(0, firstDriver, firstMain, null), key()), "E1603", "INVALID_STATE");
        assertThat(ownedDigest()).isEqualTo(before);
        JsonNode closed = acceptedOrder("LOCAL"); UUID closedTask = firstTask(closed);
        jdbc.update("UPDATE logistics.order_core SET lifecycle_status='CLOSED' WHERE id=?", uuid(closed)); before = ownedDigest();
        business(admin.write("POST", "/tms/tasks/" + closedTask + "/dispatch", selection(0, firstDriver, firstMain, null), key()), "E1603", "INVALID_STATE");
        assertThat(ownedDigest()).isEqualTo(before);
        JsonNode legacy = acceptedOrder("LOCAL"); UUID legacyTask = firstTask(legacy);
        // Nullable source columns may exist in legacy ACCEPTED rows. Do not
        // turn an invalid dispatch source into a server error or invent a kind.
        jdbc.update("UPDATE logistics.cargo_item SET item_kind=NULL WHERE order_id=?", uuid(legacy));
        before = ownedDigest(); Map<String, String> sourceBefore = tomDigest();
        assertThat(taskSources.find(legacyTask).orElseThrow().source().item().itemKind()).isNull();
        business(admin.write("POST", "/tms/tasks/" + legacyTask + "/dispatch", selection(0, firstDriver, firstMain, null), key()), "E1603", "INVALID_STATE");
        assertThat(ownedDigest()).isEqualTo(before); assertThat(tomDigest()).isEqualTo(sourceBefore);
    }

    @Test
    void databaseConstraintsEnforceSameOrderExclusiveVehicleNamespaceAndImmutableHistory() throws Exception {
        JsonNode order = acceptedOrder("LOCAL"), other = acceptedOrder("LOCAL"); UUID task = firstTask(order);
        JsonNode result = assign(admin, task, 0, firstDriver, firstMain, null, key()); UUID assignmentId = uuid(result.get("assignment"));
        assertThatThrownBy(() -> jdbc.update("UPDATE logistics.tms_task_dispatch SET order_id=? WHERE task_id=?", uuid(other), task)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO logistics.tms_resource_reservation(id,assignment_id,vehicle_id,position,acquired_at) VALUES (?,?,?,'TRAILER',CURRENT_TIMESTAMP)", UUID.randomUUID(), assignmentId, uuid(firstMain))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO logistics.tms_resource_reservation(id,assignment_id,driver_id,vehicle_id,position,acquired_at) VALUES (?,?,?,?,'TRAILER',CURRENT_TIMESTAMP)", UUID.randomUUID(), assignmentId, uuid(firstDriver), uuid(secondTrailer))).isInstanceOf(DataAccessException.class);
        Map<String, String> before = ownedDigest();
        assertThatThrownBy(() -> jdbc.update("UPDATE logistics.tms_assignment SET source_snapshot='{}'::json WHERE id=?", assignmentId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM logistics.tms_assignment WHERE id=?", assignmentId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE logistics.tms_dispatch_event SET reason='不能改歷史' WHERE task_id=?", task)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM logistics.tms_dispatch_event WHERE task_id=?", task)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE logistics.tms_resource_reservation SET acquired_at=acquired_at+INTERVAL '1 second' WHERE assignment_id=?", assignmentId)).isInstanceOf(DataAccessException.class);
        assertThat(ownedDigest()).isEqualTo(before);
        ok(admin.write("POST", "/tms/dispatches/" + assignmentId + "/revoke", fields("expectedGeneration", 1, "reason", "合成不可變歷史驗收"), key()));
        before = ownedDigest();
        assertThatThrownBy(() -> jdbc.update("UPDATE logistics.tms_assignment SET end_reason='不能改終態' WHERE id=?", assignmentId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE logistics.tms_resource_reservation SET release_reason='不能重寫釋放' WHERE assignment_id=?", assignmentId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM logistics.tms_resource_reservation WHERE assignment_id=?", assignmentId)).isInstanceOf(DataAccessException.class);
        assertThat(ownedDigest()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT data_type FROM information_schema.columns WHERE table_schema='logistics' AND table_name LIKE 'tms_%' AND column_name IN ('snapshot','source_snapshot','driver_snapshot','main_vehicle_snapshot','trailer_snapshot','original_data')", String.class)).containsOnly("json");
    }

    @Test
    void serverClockActorAndMicrosecondAuditAreConsistentInResponseEventAndDatabase() throws Exception {
        UUID task = firstTask(acceptedOrder("LOCAL")); clock.advance(Duration.ofNanos(123456789));
        Instant expected = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        JsonNode assigned = assign(admin, task, 0, firstDriver, firstMain, null, key()).get("assignment");
        assertThat(Instant.parse(assigned.get("assignedAt").asString())).isEqualTo(expected);
        JsonNode event = assigned.get("events").get(0);
        assertThat(Instant.parse(event.get("occurredAt").asString())).isEqualTo(expected);
        assertThat(event.get("actorId").asString()).isEqualTo(actor.id().toString());
        assertThat(event.get("fromGeneration").asLong()).isZero(); assertThat(event.get("toGeneration").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT assigned_at FROM logistics.tms_assignment WHERE id=?", OffsetDateTime.class, uuid(assigned)).toInstant()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM logistics.tms_dispatch_event WHERE task_id=?", OffsetDateTime.class, task).toInstant()).isEqualTo(expected);
        for (JsonNode reservation : assigned.get("reservations")) assertThat(Instant.parse(reservation.get("acquiredAt").asString())).isEqualTo(expected);
    }

    private UUID dictionaryItem(String categoryCode, String code, String label) throws Exception {
        JsonNode categories = page(admin.get("/adm/categories?size=100")); UUID category = null;
        for (JsonNode value : categories) if (value.get("code").asString().equals(categoryCode)) category = uuid(value);
        assertThat(category).isNotNull();
        return uuid(data(created(admin.write("POST", "/adm/categories/" + category + "/items",
                fields("code", code, "label", label, "enabled", true, "sortOrder", 1), null))));
    }

    private JsonNode driver(Account account) throws Exception {
        return data(created(admin.write("POST", "/fms/drivers", fields("driverCode", "SYNTHETIC_TMS_IT_D" + (++driverSequence),
                "name", "合成派工司機 " + driverSequence, "dutyStatus", "ON_DUTY", "accountId", account == null ? null : account.id().toString()), null)));
    }

    private JsonNode vehicle(UUID type) throws Exception {
        return data(created(admin.write("POST", "/fms/vehicles", fields("plateNo", plate(), "vehicleType", type.toString(),
                "capacityTon", "12.3400", "inspectionDueDate", "0001-01-01"), null)));
    }

    private String plate() { return "SYN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT); }
    private String key() { return UUID.randomUUID().toString(); }
    private String id(JsonNode value) { return value.get("id").asString(); }
    private UUID uuid(JsonNode value) { return UUID.fromString(id(value)); }
    private static Map<String, Object> fields(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private JsonNode draftOrder(String type) throws Exception {
        JsonNode customer = data(created(admin.write("POST", "/crm/customers",
                fields("customerCode", "SYN-TMS-" + key().toUpperCase(java.util.Locale.ROOT), "name", "合成派工客戶，不是真實資料"), null)));
        ObjectNode draft = (ObjectNode) TomContract.example("graphCases", "DEMO-" + type + "-CONTRACT").get("draft").deepCopy();
        draft.put("customerId", id(customer)); draft.put("remark", "不得出現在司機摘要的整單備註");
        for (JsonNode task : draft.get("tasks")) ((ObjectNode) task).put("taskKind", "SYNTHETIC_TMS_IT_" + task.get("clientRef").asString());
        ((ObjectNode) draft.get("items").get(0)).put("grossWeight", "0.0000000000000000001");
        ObjectNode first = (ObjectNode) draft.get("tasks").get(0);
        first.put("pickupAddress", " 合成\u0000原始地址 e\u0301 ");
        first.put("plannedStartAt", "1500-01-02T00:00:00.123456+23:59");
        first.put("plannedEndAt", "1500-01-02T01:00:00.123456+23:59");
        first.set("siteContact", json.createObjectNode());
        var created = admin.write("POST", "/orders", draft, key()); tom(created, 201);
        return data(created);
    }

    private JsonNode acceptedOrder(String type) throws Exception {
        JsonNode created = draftOrder(type);
        var accepted = admin.write("POST", "/orders/" + id(created) + "/accept", fields("expectedVersion", 0), key());
        tom(accepted, 200); return data(accepted);
    }

    private UUID firstTask(JsonNode order) {
        for (JsonNode task : order.get("tasks")) if (task.get("predecessorRefs").isEmpty()) return uuid(task);
        throw new AssertionError("Synthetic order must have a first task");
    }

    private UUID dependentTask(JsonNode order) {
        for (JsonNode task : order.get("tasks")) if (!task.get("predecessorRefs").isEmpty()) return uuid(task);
        throw new AssertionError("Synthetic order must have a dependent task");
    }

    private Map<String, Object> selection(long generation, JsonNode driver, JsonNode main, JsonNode trailer) {
        return fields("expectedGeneration", generation, "driverId", id(driver), "mainVehicleId", id(main), "trailerId", trailer == null ? null : id(trailer));
    }

    private Map<String, Object> replacement(long generation, JsonNode driver, JsonNode main, JsonNode trailer, String reason) {
        var result = selection(generation, driver, main, trailer); result.put("reason", reason); return result;
    }

    private JsonNode assign(Browser browser, UUID task, long generation, JsonNode driver, JsonNode main, JsonNode trailer, String key) throws Exception {
        var reply = browser.write("POST", "/tms/tasks/" + task + "/dispatch", selection(generation, driver, main, trailer), key);
        ok(reply); assertThat(reply.headers().firstValue("Idempotency-Replayed")).contains("false");
        return data(reply);
    }

    private JsonNode detail(Browser browser, UUID task) throws Exception {
        JsonNode result = data(ok(browser.get("/tms/tasks/" + task + "/dispatch")));
        shape(result, List.of("task", "current", "history")); shape(result.get("history"), PAGE_FIELDS); return result;
    }

    private JsonNode patch(String path, JsonNode prior, Map<String, Object> changes) throws Exception {
        var body = new LinkedHashMap<>(changes); body.put("version", prior.get("version").asLong());
        return data(ok(admin.write("PATCH", path, body, null)));
    }

    private void assignment(JsonNode value, String status, long version, long generation, int reservations) {
        shape(value, ASSIGNMENT_FIELDS); assertThat(value.get("status").asString()).isEqualTo(status);
        assertThat(value.get("version").asLong()).isEqualTo(version);
        assertThat(value.get("assignedGeneration").asLong()).isEqualTo(generation);
        assertThat(value.get("reservations")).hasSize(reservations);
        assertThat(value.get("events").size()).isBetween(1, 2);
        assertThat(value.get("endedAt").isNull()).isEqualTo(status.equals("ASSIGNED"));
        assertThat(value.get("endedBy").isNull()).isEqualTo(status.equals("ASSIGNED"));
        assertThat(value.get("endedGeneration").isNull()).isEqualTo(status.equals("ASSIGNED"));
    }

    private long activeReservations() { return jdbc.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL", Long.class); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM logistics." + table, Long.class); }
    private Map<String, String> ownedDigest() {
        var result = new LinkedHashMap<String, String>();
        for (String table : OWNED_TABLES) result.put(table, digest(table, table.equals("tms_task_dispatch") ? "task_id" : "id"));
        return result;
    }
    private Map<String, String> tomDigest() {
        var result = new LinkedHashMap<String, String>();
        for (String table : List.of("order_core", "tom_order", "cargo_item", "transport_task", "task_dependency", "order_draft_revision", "order_acceptance_event", "api_idempotency"))
            result.put(table, digest(table, table.equals("tom_order") ? "order_id" : table.equals("task_dependency") ? "task_id,predecessor_task_id" : "id"));
        return result;
    }
    private String digest(String table, String ordering) {
        return jdbc.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY " + ordering + ")::text,'[]')) FROM logistics." + table + " t", String.class);
    }

    private JsonNode body(HttpResponse<String> reply) { return json.readTree(reply.body()); }
    private JsonNode data(HttpResponse<String> reply) { return body(reply).get("data"); }
    private JsonNode page(HttpResponse<String> reply) { JsonNode result = data(ok(reply)); shape(result, PAGE_FIELDS); return result.get("content"); }
    private HttpResponse<String> ok(HttpResponse<String> reply) { response(reply, 200, "0000"); return reply; }
    private HttpResponse<String> created(HttpResponse<String> reply) { response(reply, 201, "0000"); return reply; }
    private void shape(JsonNode value, List<String> fields) { assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(fields); }
    private void response(HttpResponse<String> reply, int status, String code) {
        assertThat(reply.statusCode()).as("HTTP status; code %s", body(reply).get("code").asString()).isEqualTo(status);
        JsonNode envelope = body(reply); shape(envelope, List.of("code", "message", "data", "timestamp"));
        assertThat(envelope.get("code").asString()).isEqualTo(code);
        assertThat(envelope.get("message").asString()).isNotBlank();
        assertThat(OffsetDateTime.parse(envelope.get("timestamp").asString()).getOffset()).isEqualTo(java.time.ZoneOffset.ofHours(8));
    }
    private void business(HttpResponse<String> reply, String code, String reason) {
        response(reply, 409, code); assertThat(data(reply).get("reason").asString()).isEqualTo(reason);
    }
    private void tom(HttpResponse<String> reply, int status) {
        assertThat(reply.statusCode()).isEqualTo(status); assertThat(body(reply).get("code").asString()).isEqualTo("OK");
        TomContract.assertSchema("OrderEnvelope", body(reply));
    }

    private Account account(String role, boolean temporary) throws Exception {
        String username = "tms-test-" + key().substring(0, 12), password = UpmsTestData.password();
        try (var connection = dataSource.getConnection()) {
            return new Account(UpmsTestData.insert(connection, encoder, username, role, password, temporary, clock.instant()), username, password);
        }
    }
    private Browser browser(Account account) throws Exception {
        var browser = new Browser(); response(browser.write("POST", "/auth/login", fields("username", account.username(), "password", account.password()), null), 200, "0000"); return browser;
    }
    private Map<String, Object> accountChange(Account account, String role, boolean enabled) {
        return fields("displayName", "合成測試帳號", "role", role, "enabled", enabled,
                "version", jdbc.queryForObject("SELECT version FROM logistics.upms_account WHERE id=?", Long.class, account.id()));
    }
    private void canonical(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement()) { statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close(); }
    }
    private void pause(String table, String event) {
        jdbc.execute("CREATE FUNCTION logistics.test_tms_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202604,4); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_tms_pause " + event + " ON logistics." + table + " FOR EACH ROW EXECUTE FUNCTION logistics.test_tms_pause()");
    }
    private void unpause(String table) {
        jdbc.execute("DROP TRIGGER test_tms_pause ON logistics." + table); jdbc.execute("DROP FUNCTION logistics.test_tms_pause()");
    }
    private void advisory(java.sql.Connection connection, boolean acquire) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute("SELECT pg_advisory_" + (acquire ? "lock" : "unlock") + "(202604,4)"); }
    }
    private void awaitLock(String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var observer = dataSource.getConnection(); var statement = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")) {
            assertThat(observer.getAutoCommit()).isTrue(); statement.setString(1, "%" + table + "%");
            while (System.nanoTime() < deadline) {
                try (var result = statement.executeQuery()) { result.next(); if (result.getLong(1) > 0) return; }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("Expected a real PostgreSQL dispatch lock for " + table);
    }
    private void failStorage(String table, String event, boolean foreignKey) {
        String action = foreignKey ? "NEW.actor_id='00000000-0000-4000-8000-000000009999'; RETURN NEW;" : "RAISE EXCEPTION 'synthetic TMS storage failure';";
        jdbc.execute("CREATE FUNCTION logistics.test_tms_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " + action + " END $$");
        jdbc.execute("CREATE TRIGGER test_tms_fail " + event + " ON logistics." + table + " FOR EACH ROW EXECUTE FUNCTION logistics.test_tms_fail()");
    }
    private void dropFailure(String table) {
        jdbc.execute("DROP TRIGGER test_tms_fail ON logistics." + table); jdbc.execute("DROP FUNCTION logistics.test_tms_fail()");
    }
    private URI uri(String path) { return URI.create("https://localhost:" + port + "/api/v1" + path); }
    private String encodeQuery(String value) { return value.replace("合成", URLEncoder.encode("合成", StandardCharsets.UTF_8)); }
    private record Account(UUID id, String username, String password) {}
    private record Command(String path, Map<String, Object> body) {}
    private record Replay(String path, Map<String, Object> body, String key, JsonNode result) {}
    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).sslContext(TlsTestSupport.trustedContext())
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }
        String csrf() throws Exception { var reply = get("/auth/csrf"); ok(reply); return data(reply).get("token").asString(); }
        HttpResponse<String> write(String method, String path, Object payload, String key) throws Exception {
            return send(method, path, payload == null ? null : json.writeValueAsString(payload), key, csrf());
        }
        HttpResponse<String> send(String method, String path, String payload, String key, String token) throws Exception {
            var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
            if (payload != null) builder.header("Content-Type", "application/json");
            if (token != null) builder.header("X-CSRF-TOKEN", token);
            if (key != null) builder.header("Idempotency-Key", key);
            return client.send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
