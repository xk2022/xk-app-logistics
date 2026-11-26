package com.xksystem.logistics.tom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.support.MutableTestClock;
import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.TomContract;
import com.xksystem.logistics.support.UpmsTestData;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
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

/** Preserved order contract exercised through real TLS, authentication filters and PostgreSQL transactions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TomIT.TimeConfiguration.class)
class TomIT {
    private static final String CUSTOMER_A = "00000000-0000-4000-8000-000000000101";
    private static final String CUSTOMER_B = "00000000-0000-4000-8000-000000000102";
    private static final String CUSTOMER_C = "00000000-0000-4000-8000-000000000103";
    private static final List<String> TABLES = List.of("order_core", "tom_order", "cargo_item", "transport_task", "task_dependency",
            "order_draft_revision", "api_idempotency");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres")).withStartupAttempts(3);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        TlsTestSupport.register(registry);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableTestClock tomClock() { return new MutableTestClock(); }
    }

    @LocalServerPort int port;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private Account actor;
    private Browser admin;

    @BeforeEach
    void isolateSyntheticAccountsCustomersAndOrders() throws Exception {
        jdbc.execute("TRUNCATE TABLE logistics.tom_order_closure_command,logistics.tom_order_closure_task,logistics.tom_order_closure,logistics.tms_completion_confirmation_command,logistics.tms_completion_confirmation,logistics.tms_completion_command,logistics.tms_completion,logistics.tms_exception_event_evidence,logistics.tms_exception_supplement_evidence,logistics.tms_exception_command,logistics.tms_exception_event,logistics.tms_exception_supplement_request,logistics.tms_exception,logistics.tms_execution_evidence_command,logistics.tms_execution_event_evidence,logistics.tms_execution_evidence,logistics.tms_execution_evidence_object,logistics.tms_execution_command,logistics.tms_execution_event,logistics.tms_execution,logistics.tms_resource_reservation,logistics.tms_dispatch_event,logistics.tms_idempotency,logistics.tms_assignment,logistics.tms_task_dispatch,logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle, " + TABLES.stream().map(table -> "logistics." + table).collect(java.util.stream.Collectors.joining(","))
                + ", logistics.crm_contact, logistics.crm_address, logistics.crm_customer, logistics.adm_change_log, logistics.upms_audit, logistics.upms_account");
        clock.reset(); actor = account("ADMIN", false); admin = new Browser(); login(admin, actor);
        int index = 0;
        for (JsonNode customer : TomContract.examples().get("metadata").get("syntheticCustomers")) {
            jdbc.update("""
                    INSERT INTO logistics.crm_customer
                        (id,customer_code,name,enabled,version,created_at,updated_at,created_by,updated_by)
                    VALUES (?,?,?,true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                    """, UUID.fromString(customer.get("id").asString()), "TOM_SYNTHETIC_" + (++index),
                    customer.get("name").asString(), actor.id(), actor.id());
        }
    }

    static Stream<Arguments> positiveRequests() {
        return TomContract.group("schemaPositive").stream().filter(value -> Set.of("CreateDraftRequest", "ReplaceDraftRequest").contains(value.get("schema").asString()))
                .map(value -> Arguments.of(value.get("id").asString(), value));
    }

    @ParameterizedTest(name = "original HTTP positive request {0}")
    @MethodSource("positiveRequests")
    void originalPositiveBodiesCreateOrReplaceARealAggregateAndRereadTheSameVersion(String name, JsonNode example) throws Exception {
        HttpResponse<String> response;
        if (example.get("schema").asString().equals("ReplaceDraftRequest")) {
            JsonNode initial = create(admin, minimal(CUSTOMER_C), key());
            response = admin.order("PUT", "/orders/" + id(initial), example.get("value"), key(), true);
            legacy(response, 200, "OK", "OrderEnvelope");
        } else {
            response = admin.order("POST", "/orders", example.get("value"), key(), true);
            legacy(response, 201, "OK", "OrderEnvelope");
        }
        JsonNode order = data(response);
        assertThat(order.get("lifecycleStatus").asString()).isEqualTo("OPEN");
        assertThat(order.get("acceptanceStatus").asString()).isEqualTo("DRAFT");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        HttpResponse<String> reread = admin.get("/orders/" + id(order)); legacy(reread, 200, "OK", "OrderEnvelope");
        assertThat(data(reread)).isEqualTo(order);
        assertThat(count("order_draft_revision")).isEqualTo(order.get("version").asLong() + 1);
    }

    static Stream<Arguments> negativeRequests() {
        return TomContract.group("schemaNegative").stream().filter(value -> Set.of("CreateDraftRequest", "ReplaceDraftRequest").contains(value.get("schema").asString()))
                .map(value -> Arguments.of(value.get("id").asString(), value));
    }

    @ParameterizedTest(name = "original HTTP negative request {0}")
    @MethodSource("negativeRequests")
    void originalNegativeBodiesReturnLegacyFieldErrorsAndLeaveNoPartialWrites(String name, JsonNode example) throws Exception {
        boolean replace = example.get("schema").asString().equals("ReplaceDraftRequest");
        JsonNode initial = replace ? create(admin, minimal(CUSTOMER_C), key()) : null;
        Map<String, Long> before = counts();
        HttpResponse<String> response = admin.order(replace ? "PUT" : "POST", replace ? "/orders/" + id(initial) : "/orders", example.get("value"), key(), true);
        legacy(response, 400, "VALIDATION_ERROR", "ErrorEnvelope");
        assertThat(body(response).get("fieldErrors").size()).isPositive();
        assertThat(counts()).isEqualTo(before);
        if (replace) assertThat(data(admin.get("/orders/" + id(initial)))).isEqualTo(initial);
    }

    static Stream<Arguments> graphs() { return TomContract.group("graphCases").stream().map(value -> Arguments.of(value.get("id").asString(), value)); }

    @ParameterizedTest(name = "original HTTP graph {0}")
    @MethodSource("graphs")
    void allOriginalGraphFixturesAreValidatedAgainstRealPostgres(String name, JsonNode example) throws Exception {
        Map<String, Long> before = counts();
        HttpResponse<String> response = admin.order("POST", "/orders", example.get("draft"), key(), true);
        if (example.get("expected").asString().equals("valid_graph")) {
            legacy(response, 201, "OK", "OrderEnvelope");
            JsonNode checked = check(admin, id(data(response)));
            assertThat(checked.get("confirmedRulesOutcome").asString()).isEqualTo("COMPLETE");
            assertThat(checked.get("missingFields").size()).isZero();
        } else {
            legacy(response, 400, "VALIDATION_ERROR", "ErrorEnvelope"); assertThat(counts()).isEqualTo(before);
        }
    }

    static Stream<Arguments> invalidScalars() {
        return TomContract.group("schemaNegative").stream().filter(value -> Set.of("NullableDateTime", "RefKey", "NullableDecimal").contains(value.get("schema").asString()))
                .map(value -> Arguments.of(value.get("id").asString(), value));
    }

    @ParameterizedTest(name = "original scalar embedded in HTTP draft {0}")
    @MethodSource("invalidScalars")
    void scalarCounterexamplesAreEmbeddedInTheCorrectRequestFields(String name, JsonNode example) throws Exception {
        ObjectNode draft = minimal(CUSTOMER_A); String schema = example.get("schema").asString();
        if (schema.equals("NullableDateTime")) draft.set("shipping", json.createObjectNode().set("eta", example.get("value")));
        else {
            ObjectNode item = json.createObjectNode().put("clientRef", "item");
            item.set(schema.equals("RefKey") ? "clientRef" : "grossWeight", example.get("value")); draft.set("items", json.createArrayNode().add(item));
        }
        legacy(admin.order("POST", "/orders", draft, key(), true), 400, "VALIDATION_ERROR", "ErrorEnvelope");
        assertThat(count("order_core")).isZero(); assertThat(count("api_idempotency")).isZero();
    }

    @Test
    void allFifteenRoleBindingsUseThePreservedEnvelopeAndCurrentAuthorization() throws Exception {
        JsonNode initial = create(admin, minimal(CUSTOMER_A), key());
        JsonNode policy = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/06_api/order-draft-access-policy.json")));
        for (String role : List.of("ADMIN", "DISPATCHER", "DRIVER")) {
            Browser member = new Browser(); login(member, account(role, false));
            for (JsonNode binding : policy.get("bindings")) {
                if (!binding.get("singleLogicalRole").asString().equals(role)) continue;
                String method = binding.get("method").asString();
                String path = binding.get("path").asString().replace("{orderId}", id(initial));
                JsonNode request = method.equals("POST") ? minimal(CUSTOMER_A)
                        : method.equals("PUT") ? replacement(data(admin.get("/orders/" + id(initial))).get("version").asLong(), minimal(CUSTOMER_A)) : null;
                HttpResponse<String> response = member.order(method, path, request, method.equals("GET") ? null : key(), true);
                if (role.equals("DRIVER")) legacy(response, 403, "FORBIDDEN", "ErrorEnvelope");
                else {
                    String schema = binding.get("operationId").asString().equals("listOrderDrafts") ? "OrderPageEnvelope"
                            : binding.get("operationId").asString().equals("checkOrderAcceptanceFields") ? "AcceptanceCheckEnvelope" : "OrderEnvelope";
                    legacy(response, method.equals("POST") ? 201 : 200, "OK", schema);
                }
            }
        }
    }

    @Test
    void authenticationCsrfForcePasswordAndMalformedRequestsNeverFallBackToTheUpmsEnvelope() throws Exception {
        Browser anonymous = new Browser();
        legacy(anonymous.get("/orders"), 401, "UNAUTHENTICATED", "ErrorEnvelope");
        legacy(anonymous.order("POST", "/orders", minimal(CUSTOMER_A), key(), false), 401, "UNAUTHENTICATED", "ErrorEnvelope");
        Browser temporary = new Browser(); login(temporary, account("ADMIN", true));
        legacy(temporary.get("/orders"), 403, "FORBIDDEN", "ErrorEnvelope");
        legacy(admin.order("POST", "/orders", minimal(CUSTOMER_A), key(), false), 403, "CSRF_INVALID", "ErrorEnvelope");
        legacy(admin.raw("POST", "/orders", "{\"type\":\"LOCAL\",\"type\":\"IMPORT\"}", key(), true), 400, "VALIDATION_ERROR", "ErrorEnvelope");
        legacy(admin.raw("POST", "/orders", "{", key(), true), 400, "VALIDATION_ERROR", "ErrorEnvelope");
        legacy(admin.order("POST", "/orders?unknown=1", minimal(CUSTOMER_A), key(), true), 400, "VALIDATION_ERROR", "ErrorEnvelope");
        legacy(admin.order("DELETE", "/orders/" + UUID.randomUUID(), null, null, true), 400, "VALIDATION_ERROR", "ErrorEnvelope");
        legacy(admin.get("/orders/" + UUID.randomUUID() + "/accept"), 404, "ORDER_NOT_FOUND", "ErrorEnvelope");
        legacy(admin.get("/orders/1-1-1-1-1"), 400, "VALIDATION_ERROR", "ErrorEnvelope");
        legacy(admin.get("/orders/" + UUID.randomUUID()), 404, "ORDER_NOT_FOUND", "ErrorEnvelope");
    }

    @Test
    void createCapturesTheLockedCustomerNameAndRenameDoesNotOverwriteSameCustomerHistory() throws Exception {
        JsonNode original = create(admin, minimal(CUSTOMER_A), key()); String name = original.get("customerName").asString();
        upms(admin.raw("PATCH", "/crm/customers/" + CUSTOMER_A, "{\"version\":0,\"name\":\"合成已改名客戶\"}", null, true), 200);
        assertThat(data(admin.get("/orders/" + id(original))).get("customerName").asString()).isEqualTo(name);
        JsonNode edited = replace(admin, id(original), 0, minimal(CUSTOMER_A), key());
        assertThat(edited.get("customerName").asString()).isEqualTo(name);
        upms(admin.raw("PATCH", "/crm/customers/" + CUSTOMER_A, "{\"version\":1,\"enabled\":false}", null, true), 200);
        JsonNode disabledHistory = replace(admin, id(original), 1, minimal(CUSTOMER_A), key());
        assertThat(disabledHistory.get("customerName").asString()).isEqualTo(name);
        assertThat(check(admin, id(original)).get("confirmedRulesOutcome").asString()).isEqualTo("INCOMPLETE");
        JsonNode switched = replace(admin, id(original), 2, minimal(CUSTOMER_B), key());
        assertThat(switched.get("customerId").asString()).isEqualTo(CUSTOMER_B);
        assertThat(switched.get("customerName").asString()).isEqualTo("示意客戶乙");
        legacy(admin.order("POST", "/orders", minimal(CUSTOMER_A), key(), true), 400, "INVALID_CUSTOMER", "ErrorEnvelope");
        legacy(admin.order("PUT", "/orders/" + id(original), replacement(3, minimal(CUSTOMER_A)), key(), true), 400, "INVALID_CUSTOMER", "ErrorEnvelope");
        assertThat(data(admin.get("/orders/" + id(original)))).isEqualTo(switched);
    }

    @Test
    void sameKeyReplayReturnsTheOriginalSnapshotBeforeCurrentVersionOrCustomerChecks() throws Exception {
        ObjectNode firstBody = minimal(CUSTOMER_A); String postKey = key();
        HttpResponse<String> first = admin.order("POST", "/orders", firstBody, postKey, true); legacy(first, 201, "OK", "OrderEnvelope");
        JsonNode original = data(first); String location = first.headers().firstValue("Location").orElseThrow();
        ObjectNode draft = minimal(CUSTOMER_A).put("remark", "合成版本一"); String putKey = key();
        HttpResponse<String> put = admin.order("PUT", "/orders/" + id(original), replacement(0, draft), putKey, true); legacy(put, 200, "OK", "OrderEnvelope");
        JsonNode versionOne = data(put); replace(admin, id(original), 1, minimal(CUSTOMER_B), key());
        jdbc.update("UPDATE logistics.crm_customer SET enabled=false WHERE id=?", UUID.fromString(CUSTOMER_A));
        Map<String, Long> before = counts();
        HttpResponse<String> replayPost = admin.order("POST", "/orders", firstBody, postKey, true); legacy(replayPost, 201, "OK", "OrderEnvelope");
        assertThat(data(replayPost)).isEqualTo(original); assertThat(replayPost.headers().firstValue("Location")).contains(location);
        assertThat(replayPost.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(body(replayPost).get("traceId").asString()).isNotEqualTo(body(first).get("traceId").asString());
        HttpResponse<String> replayPut = admin.order("PUT", "/orders/" + id(original), replacement(0, draft), putKey, true); legacy(replayPut, 200, "OK", "OrderEnvelope");
        assertThat(data(replayPut)).isEqualTo(versionOne); assertThat(counts()).isEqualTo(before);
        assertThat(data(admin.get("/orders/" + id(original))).get("version").asLong()).isEqualTo(2);
    }

    @Test
    void rawCanonicalBodyDistinctionsAndActorMethodPathScopesAreNotCollapsed() throws Exception {
        String requestKey = key(); ObjectNode body = minimal(CUSTOMER_A); JsonNode original = create(admin, body, requestKey);
        String reordered = "{\"customerId\":\"" + CUSTOMER_A + "\",\"type\":\"LOCAL\"}";
        HttpResponse<String> replay = admin.raw("POST", "/orders", reordered, requestKey, true); legacy(replay, 201, "OK", "OrderEnvelope");
        assertThat(data(replay)).isEqualTo(original); assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        legacy(admin.order("POST", "/orders", body.deepCopy().putNull("remark"), requestKey, true), 409, "IDEMPOTENCY_KEY_REUSED", "ErrorEnvelope");
        String putKey = key(); JsonNode v1 = replace(admin, id(original), 0, body, putKey);
        HttpResponse<String> canonicalPathReplay=admin.order("PUT","/orders/"+id(original).toUpperCase(java.util.Locale.ROOT),replacement(0,body),putKey,true);
        legacy(canonicalPathReplay,200,"OK","OrderEnvelope");assertThat(data(canonicalPathReplay)).isEqualTo(v1);assertThat(canonicalPathReplay.headers().firstValue("Idempotency-Replayed")).contains("true");
        legacy(admin.order("PUT", "/orders/" + id(original), replacement(1, body), putKey, true), 409, "IDEMPOTENCY_KEY_REUSED", "ErrorEnvelope");
        legacy(admin.order("PUT", "/orders/" + id(original), replacement(0, body), key(), true), 409, "VERSION_CONFLICT", "ErrorEnvelope");
        assertThat(data(admin.get("/orders/" + id(original)))).isEqualTo(v1);
        Browser dispatcher = new Browser(); login(dispatcher, account("DISPATCHER", false));
        JsonNode independent = create(dispatcher, body, requestKey); assertThat(id(independent)).isNotEqualTo(id(original));
        replace(admin, id(independent), 0, body, putKey); // Same key/method but another canonical resource path.
        replace(admin, id(original), 1, body, requestKey); // POST and PUT key scopes are distinct.
    }

    @Test
    void successfulReplaysStillRequireCurrentRoleSessionAndCsrfAndKeysDoNotExpireImplicitly() throws Exception {
        String requestKey = key(); ObjectNode draft = minimal(CUSTOMER_A); JsonNode original = create(admin, draft, requestKey); String token=admin.csrf();
        legacy(admin.order("POST", "/orders", draft, requestKey, false), 403, "CSRF_INVALID", "ErrorEnvelope");
        jdbc.update("UPDATE logistics.upms_account SET role='DRIVER',auth_version=auth_version+1 WHERE id=?", actor.id());
        legacy(admin.withToken("POST", "/orders", draft.toString(), requestKey, token), 401, "UNAUTHENTICATED", "ErrorEnvelope");
        HttpResponse<String> refreshed=admin.get("/auth/csrf");upms(refreshed,200);
        upms(admin.withToken("POST","/auth/login",json.writeValueAsString(Map.of("username",actor.username(),"password",actor.password())),null,data(refreshed).get("token").asString()),200);
        Browser driver = admin;
        legacy(driver.order("POST", "/orders", draft, requestKey, true), 403, "FORBIDDEN", "ErrorEnvelope");
        jdbc.update("UPDATE logistics.upms_account SET role='ADMIN',auth_version=auth_version+1 WHERE id=?", actor.id());
        clock.advance(Duration.ofDays(2));
        Browser restored = new Browser(); login(restored, actor);
        HttpResponse<String> replay = restored.order("POST", "/orders", draft, requestKey, true); legacy(replay, 201, "OK", "OrderEnvelope");
        assertThat(data(replay)).isEqualTo(original); assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(count("order_core")).isEqualTo(1);
    }

    @Test
    void fullReplacementRetainsRefsAndTaskNumbersResetsOmissionsAndPreservesNullVersusEmptyObjects() throws Exception {
        ObjectNode draft = simpleGraph(); draft.put("remark", "合成會被清空"); draft.put("customerReference", "合成會被清空");
        draft.set("shipping", json.createObjectNode()); ((ObjectNode) draft.get("tasks").get(0)).set("siteContact", json.createObjectNode());
        JsonNode original = create(admin, draft, key());
        assertThat(original.get("shipping").isObject()).isTrue(); assertThat(original.get("shipping").size()).isEqualTo(7);
        assertThat(original.get("tasks").get(0).get("siteContact").isObject()).isTrue();
        Map<String, JsonNode> oldItems = byRef(original.get("items")), oldTasks = byRef(original.get("tasks"));
        ObjectNode reordered = simpleGraph();
        reordered.set("items", json.createArrayNode().add(reordered.get("items").get(1)).add(reordered.get("items").get(0)));
        reordered.set("tasks", json.createArrayNode().add(reordered.get("tasks").get(1)).add(reordered.get("tasks").get(0)));
        JsonNode v1 = replace(admin, id(original), 0, reordered, key());
        assertThat(v1.get("remark").isNull()).isTrue(); assertThat(v1.get("customerReference").isNull()).isTrue(); assertThat(v1.get("shipping").isNull()).isTrue();
        for (var entry : byRef(v1.get("items")).entrySet()) assertThat(entry.getValue().get("id")).isEqualTo(oldItems.get(entry.getKey()).get("id"));
        for (var entry : byRef(v1.get("tasks")).entrySet()) {
            assertThat(entry.getValue().get("id")).isEqualTo(oldTasks.get(entry.getKey()).get("id"));
            assertThat(entry.getValue().get("taskNo")).isEqualTo(oldTasks.get(entry.getKey()).get("taskNo"));
            assertThat(entry.getValue().get("siteContact").isNull()).isTrue();
        }
        JsonNode v2 = replace(admin, id(original), 1, minimal(CUSTOMER_C), key());
        assertThat(v2.get("items").size()).isZero(); assertThat(v2.get("tasks").size()).isZero();
        JsonNode recreated = replace(admin, id(original), 2, simpleGraph(), key());
        assertThat(byRef(recreated.get("items")).get("item-a").get("id")).isNotEqualTo(oldItems.get("item-a").get("id"));
        assertThat(byRef(recreated.get("tasks")).get("task-a").get("taskNo")).isNotEqualTo(oldTasks.get("task-a").get("taskNo"));
        assertThat(count("order_draft_revision")).isEqualTo(4);
    }

    @Test
    void acceptanceReadsOneVersionWithoutChangingDraftRowsRevisionsOrIdempotencyRecords() throws Exception {
        JsonNode original = create(admin, TomContract.example("graphCases", "DEMO-IMPORT-CONTRACT").get("draft"), key());
        Map<String, Long> before = counts(); JsonNode checked = check(admin, id(original));
        assertThat(checked.get("version")).isEqualTo(original.get("version"));
        assertThat(checked.get("confirmedRulesOutcome").asString()).isEqualTo("COMPLETE");
        assertThat(checked.get("operationalAcceptance").asString()).isEqualTo("NOT_GRANTED");
        assertThat(checked.get("pendingPolicyIds").size()).isPositive();
        assertThat(counts()).isEqualTo(before); assertThat(data(admin.get("/orders/" + id(original)))).isEqualTo(original);
        jdbc.update("UPDATE logistics.crm_customer SET enabled=false WHERE id=?", UUID.fromString(CUSTOMER_A));
        JsonNode disabled = check(admin, id(original));
        assertThat(disabled.get("confirmedRulesOutcome").asString()).isEqualTo("INCOMPLETE");
        boolean customerIssue = false;
        for (JsonNode issue : disabled.get("missingFields")) customerIssue |= issue.get("fieldId").asString().equals("OF04") && issue.get("ruleId").asString().equals("ORD-P01");
        assertThat(customerIssue).isTrue(); assertThat(counts()).isEqualTo(before);
    }

    @Test
    void realTomcatAliasCookieAuthenticatesAllFiveApisRotatesWithCanonicalAndRecoversAfterConflict() throws Exception {
        AliasBrowser alias = new AliasBrowser();
        String token = alias.csrf(); String anonymousId = alias.jar.get("JSESSIONID");
        assertThat(java.util.Objects.equals(alias.jar.get("XKSESSION"),anonymousId)).isTrue();
        HttpResponse<String> logged = alias.send("POST", "/auth/login", json.writeValueAsString(Map.of("username", actor.username(), "password", actor.password())), token, null, null);
        upms(logged, 200); String authenticatedId = alias.jar.get("JSESSIONID");
        assertThat(java.util.Objects.equals(authenticatedId,anonymousId)).isFalse(); assertThat(java.util.Objects.equals(alias.jar.get("XKSESSION"),authenticatedId)).isTrue();
        assertThat(logged.headers().allValues("Set-Cookie").stream().anyMatch(cookie -> cookie.startsWith("JSESSIONID=")
                &&cookie.contains("Secure")&&cookie.contains("HttpOnly")&&cookie.contains("SameSite=Lax"))).isTrue();
        token = alias.csrf();
        HttpResponse<String> posted = alias.send("POST", "/orders", minimal(CUSTOMER_A).toString(), token, key(), null);
        legacy(posted, 201, "OK", "OrderEnvelope"); JsonNode order = data(posted);
        assertThat(java.util.Objects.equals(alias.jar.get("XKSESSION"),authenticatedId)).as("Business writes preserve the explicitly authenticated session ID").isTrue();
        assertThat(java.util.Objects.equals(alias.jar.get("JSESSIONID"),authenticatedId)).isTrue();
        legacy(alias.send("GET", "/orders", null, null, null, null), 200, "OK", "OrderPageEnvelope");
        legacy(alias.send("GET", "/orders/" + id(order), null, null, null, null), 200, "OK", "OrderEnvelope");
        legacy(alias.send("PUT", "/orders/" + id(order), replacement(0, minimal(CUSTOMER_A)).toString(), token, key(), null), 200, "OK", "OrderEnvelope");
        legacy(alias.send("GET", "/orders/" + id(order) + "/acceptance-check", null, null, null, null), 200, "OK", "AcceptanceCheckEnvelope");
        legacy(alias.send("GET", "/orders", null, null, null, "JSESSIONID=" + anonymousId), 401, "UNAUTHENTICATED", "ErrorEnvelope");
        legacy(alias.send("GET", "/orders;jsessionid=" + authenticatedId, null, null, null, ""), 400, "VALIDATION_ERROR", "ErrorEnvelope");
        legacy(alias.send("GET", "/orders", null, null, null, "JSESSIONID=" + authenticatedId + "; XKSESSION=" + UUID.randomUUID()), 401, "UNAUTHENTICATED", "ErrorEnvelope");
        assertThat(alias.jar.containsKey("JSESSIONID")||alias.jar.containsKey("XKSESSION")).isFalse();
        token = alias.csrf();
        upms(alias.send("POST", "/auth/login", json.writeValueAsString(Map.of("username", actor.username(), "password", actor.password())), token, null, null), 200);
        token = alias.csrf(); String beforeLogout = alias.jar.get("JSESSIONID");
        upms(alias.send("POST", "/auth/logout", null, token, null, null), 200);
        legacy(alias.send("GET", "/orders", null, null, null, "JSESSIONID=" + beforeLogout), 401, "UNAUTHENTICATED", "ErrorEnvelope");
        token = alias.csrf(); upms(alias.send("POST", "/auth/login", json.writeValueAsString(Map.of("username", actor.username(), "password", actor.password())), token, null, null), 200);
        jdbc.update("UPDATE logistics.upms_account SET auth_version=auth_version+1 WHERE id=?", actor.id());
        legacy(alias.send("GET", "/orders", null, null, null, null), 401, "UNAUTHENTICATED", "ErrorEnvelope");
    }

    @Test
    void exactRawUnicodeNulDecimalIntegerAndExtremeOffsetValuesSurviveDatabaseRevisionAndReplay() throws Exception {
        ObjectNode draft = simpleGraph().put("remark", "\u0000合成 e\u0301 \u0000").put("customerReference", " e\u0301 ");
        draft.set("shipping", json.createObjectNode().put("shippingCompany", "\u0000合成").put("eta", "2026-01-02t00:00:00.123456+23:59"));
        ObjectNode item = (ObjectNode) draft.get("items").get(0); item.put("grossWeight", "0.0000000000000000001");
        item.put("containerNo", "合成\u0000欄位"); item.put("lastPickupDate", "2026-01-02");
        ObjectNode task = (ObjectNode) draft.get("tasks").get(0); task.put("pickupAddress", "合成\n\u0000地址");
        task.put("plannedStartAt", "2026-01-02T00:00:00-23:59"); task.put("plannedEndAt", "2026-01-03T00:00:00-23:59");
        String raw = draft.toString().replace("\"sequence\":1", "\"sequence\":1e0").replace("\"sequence\":2", "\"sequence\":2.0");
        String requestKey = key(); HttpResponse<String> first = admin.raw("POST", "/orders", raw, requestKey, true);
        legacy(first, 201, "OK", "OrderEnvelope"); JsonNode order = data(first);
        for (String field : List.of("remark", "customerReference")) assertThat(order.get(field)).isEqualTo(draft.get(field));
        assertThat(order.get("shipping").get("eta")).isEqualTo(draft.get("shipping").get("eta"));
        assertThat(jdbc.queryForObject("SELECT eta FROM logistics.tom_order WHERE order_id=?",java.sql.Timestamp.class,UUID.fromString(id(order))).toInstant())
                .isEqualTo(Instant.parse("2026-01-01T00:01:00.123456Z"));
        assertThat(jdbc.queryForObject("SELECT planned_start_at FROM logistics.transport_task WHERE order_id=? AND client_ref='task-a'",java.sql.Timestamp.class,UUID.fromString(id(order))).toInstant())
                .isEqualTo(Instant.parse("2026-01-02T23:59:00Z"));
        assertThat(byRef(order.get("items")).get("item-a").get("grossWeight").asString()).isEqualTo("0.0000000000000000001");
        assertThat(byRef(order.get("tasks")).get("task-a").get("pickupAddress").asString()).contains("\u0000");
        assertThat(data(admin.get("/orders/" + id(order)))).isEqualTo(order);
        String snapshot = jdbc.queryForObject("SELECT snapshot::text FROM logistics.order_draft_revision WHERE order_id=? AND aggregate_version=0", String.class, UUID.fromString(id(order)));
        assertThat(json.readTree(snapshot)).isEqualTo(order);
        HttpResponse<String> replay = admin.raw("POST", "/orders", raw, requestKey, true); legacy(replay, 201, "OK", "OrderEnvelope"); assertThat(data(replay)).isEqualTo(order);
        legacy(admin.raw("POST", "/orders", "{\"type\":\"LOCAL\",\"customerId\":\"" + CUSTOMER_A + "\",\"remark\":\"\\ud800\"}", key(), true), 400, "VALIDATION_ERROR", "ErrorEnvelope");
        String historicalWire="1500-01-02T00:00:00.123456+23:59";
        ObjectNode historical=minimal(CUSTOMER_A);historical.set("shipping",json.createObjectNode().put("eta",historicalWire));
        String replace = "{\"expectedVersion\":-0.0,\"draft\":" + historical + "}";
        HttpResponse<String> replaced=admin.raw("PUT", "/orders/" + id(order), replace, key(), true);legacy(replaced, 200, "OK", "OrderEnvelope");
        assertThat(data(replaced).get("shipping").get("eta").asString()).isEqualTo(historicalWire);
        Instant historicalInstant=com.xksystem.logistics.tom.domain.DraftCodec.parseInstant(historicalWire);
        java.math.BigDecimal expectedEpoch=java.math.BigDecimal.valueOf(historicalInstant.getEpochSecond())
                .add(java.math.BigDecimal.valueOf(historicalInstant.getNano(),9));
        assertThat(jdbc.queryForObject("SELECT EXTRACT(epoch FROM eta) FROM logistics.tom_order WHERE order_id=?",java.math.BigDecimal.class,UUID.fromString(id(order))))
                .isEqualByComparingTo(expectedEpoch);
    }

    @Test
    void preservedListFiltersAndFourStableSortsAcceptUnboundedNonnegativePagesWithoutInventingCaps() throws Exception {
        for (String type : List.of("IMPORT", "EXPORT", "LOCAL")) for (String customer : List.of(CUSTOMER_A, CUSTOMER_B)) create(admin, minimal(customer).put("type", type), key());
        jdbc.update("UPDATE logistics.order_core SET updated_at='2026-01-01T00:00:00Z'");
        jdbc.update("UPDATE logistics.tom_order SET updated_at='2026-01-01T00:00:00Z'");
        for (String sort : List.of("updatedAt,asc", "updatedAt,desc", "orderNo,asc", "orderNo,desc")) {
            String column = sort.startsWith("updatedAt") ? "updated_at" : "order_no";
            String direction = sort.endsWith("desc") ? "DESC" : "ASC";
            List<String> expected = jdbc.queryForList("SELECT id::text FROM logistics.order_core ORDER BY " + column + " " + direction + ",id ASC", String.class);
            List<String> seen = new ArrayList<>();
            for (int page=0;page<3;page++) {
                HttpResponse<String> response = admin.get("/orders?page=" + page + "&size=2&sort=" + sort); legacy(response, 200, "OK", "OrderPageEnvelope");
                for (JsonNode value : data(response).get("content")) seen.add(id(value));
                assertThat(data(response).get("totalElements").asLong()).isEqualTo(6);
            }
            assertThat(seen).containsExactlyElementsOf(expected);
        }
        HttpResponse<String> filtered = admin.get("/orders?type=IMPORT&customerId=" + CUSTOMER_A); legacy(filtered, 200, "OK", "OrderPageEnvelope");
        assertThat(data(filtered).get("content").size()).isEqualTo(1);
        for (String page : List.of("2147483648", "999999999999999999999999999999999999999999999999")) {
            HttpResponse<String> response = admin.get("/orders?page=" + page + "&size=1"); legacy(response, 200, "OK", "OrderPageEnvelope");
            assertThat(data(response).get("content").size()).isZero(); assertThat(data(response).get("page").toString()).isEqualTo(page);
        }
        for (String query : List.of("page=-1", "page=0.5", "page=1e0", "size=0", "size=101", "sort=customerName,asc", "sort=updatedAt", "type=local", "customerId=1-1-1-1-1", "page=0&page=1", "unknown=1"))
            legacy(admin.get("/orders?" + query), 400, "VALIDATION_ERROR", "ErrorEnvelope");
    }

    @Test
    void readonlyLifecycleAcceptanceAndExhaustedVersionBlockNewWritesButDoNotInvalidateSuccessfulReplay() throws Exception {
        String requestKey = key(); ObjectNode draft = minimal(CUSTOMER_A); JsonNode order = create(admin, draft, requestKey);
        jdbc.update("UPDATE logistics.tom_order SET version=9007199254740991 WHERE order_id=?", UUID.fromString(id(order)));
        Map<String, Long> before = counts();
        legacy(admin.order("PUT", "/orders/" + id(order), replacement(9007199254740991L, draft), key(), true), 409, "ORDER_NOT_EDITABLE", "ErrorEnvelope");
        HttpResponse<String> replay = admin.order("POST", "/orders", draft, requestKey, true); legacy(replay, 201, "OK", "OrderEnvelope"); assertThat(data(replay)).isEqualTo(order);
        assertThat(counts()).isEqualTo(before);
        jdbc.update("UPDATE logistics.tom_order SET version=0,acceptance_status='ACCEPTED' WHERE order_id=?", UUID.fromString(id(order)));
        legacy(admin.order("PUT", "/orders/" + id(order), replacement(0, draft), key(), true), 409, "ORDER_NOT_EDITABLE", "ErrorEnvelope");
        jdbc.update("UPDATE logistics.tom_order SET acceptance_status='DRAFT' WHERE order_id=?", UUID.fromString(id(order)));
        jdbc.update("UPDATE logistics.order_core SET lifecycle_status='CLOSED' WHERE id=?", UUID.fromString(id(order)));
        legacy(admin.order("PUT", "/orders/" + id(order), replacement(0, draft), key(), true), 409, "ORDER_NOT_EDITABLE", "ErrorEnvelope");
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void graphErrorsFollowExistingKeyComparisonAndFailedValidationDoesNotReserveTheKey() throws Exception {
        String requestKey = key(); ObjectNode valid = simpleGraph(); create(admin, valid, requestKey);
        ObjectNode invalid = valid.deepCopy(); ((ObjectNode) invalid.get("tasks").get(1)).put("itemRef", "missing");
        legacy(admin.order("POST", "/orders", invalid, requestKey, true), 409, "IDEMPOTENCY_KEY_REUSED", "ErrorEnvelope");
        String reusable = key(); Map<String, Long> before = counts();
        legacy(admin.order("POST", "/orders", invalid, reusable, true), 400, "VALIDATION_ERROR", "ErrorEnvelope"); assertThat(counts()).isEqualTo(before);
        create(admin, valid, reusable);
    }

    @Test
    void sequenceSwapAndRetainedExternalReferencesDoNotDeleteAndRecreateExistingChildren() throws Exception {
        JsonNode order = create(admin, simpleGraph(), key()); Map<String,JsonNode> items = byRef(order.get("items")), tasks = byRef(order.get("tasks"));
        jdbc.execute("CREATE TABLE logistics.test_tom_reference_guard(item_id uuid REFERENCES logistics.cargo_item(id),task_id uuid REFERENCES logistics.transport_task(id))");
        try {
            jdbc.update("INSERT INTO logistics.test_tom_reference_guard VALUES (?,?)", UUID.fromString(items.get("item-a").get("id").asString()), UUID.fromString(tasks.get("task-a").get("id").asString()));
            ObjectNode swapped = simpleGraph(); ((ObjectNode)swapped.get("items").get(0)).put("sequence",2); ((ObjectNode)swapped.get("items").get(1)).put("sequence",1);
            JsonNode changed = replace(admin,id(order),0,swapped,key());
            assertThat(byRef(changed.get("items")).get("item-a").get("id")).isEqualTo(items.get("item-a").get("id"));
            assertThat(byRef(changed.get("tasks")).get("task-a").get("taskNo")).isEqualTo(tasks.get("task-a").get("taskNo"));
            Map<String,Long> before=counts(); String removeKey=key();
            legacy(admin.order("PUT","/orders/"+id(order),replacement(1,minimal(CUSTOMER_C)),removeKey,true),409,"PROTECTED_RELATION","ErrorEnvelope");
            assertThat(counts()).isEqualTo(before); assertThat(data(admin.get("/orders/"+id(order)))).isEqualTo(changed);
            jdbc.execute("TRUNCATE logistics.test_tom_reference_guard"); replace(admin,id(order),1,minimal(CUSTOMER_C),removeKey);
        } finally { jdbc.execute("DROP TABLE logistics.test_tom_reference_guard"); }
    }

    @Test
    void revisionAndIdempotencyAreCommittedAtomicallyWithAllBusinessRowsAndRevisionHistoryIsImmutable() throws Exception {
        JsonNode order=create(admin,simpleGraph(),key()); Map<String,Long> before=counts(); String retryKey=key();
        jdbc.execute("CREATE FUNCTION logistics.test_tom_reject_revision() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic revision storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER test_tom_revision_failure BEFORE INSERT ON logistics.order_draft_revision FOR EACH ROW EXECUTE FUNCTION logistics.test_tom_reject_revision()");
        try {
            legacy(admin.order("PUT","/orders/"+id(order),replacement(0,minimal(CUSTOMER_B)),retryKey,true),500,"INTERNAL_ERROR","ErrorEnvelope");
            assertThat(counts()).isEqualTo(before); assertThat(data(admin.get("/orders/"+id(order)))).isEqualTo(order);
        } finally { jdbc.execute("DROP TRIGGER test_tom_revision_failure ON logistics.order_draft_revision"); jdbc.execute("DROP FUNCTION logistics.test_tom_reject_revision()"); }
        replace(admin,id(order),0,minimal(CUSTOMER_B),retryKey);
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.order_draft_revision SET request_trace_id='illegal' WHERE order_id=?",UUID.fromString(id(order)))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM logistics.order_draft_revision WHERE order_id=?",UUID.fromString(id(order)))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void sameKeyInProgressReturnsConflictWithoutDuplicatingAnAggregateAndConcurrentVersionsHaveOneWinner() throws Exception {
        String token=admin.csrf(),requestKey=key(); String raw=simpleGraph().toString();
        createPauseTrigger("order_core");
        var pool=Executors.newFixedThreadPool(2);
        try (var gate=dataSource.getConnection()) {
            jdbcLock(gate,true);
            try {
                var first=pool.submit(()->admin.withToken("POST","/orders",raw,requestKey,token));
                awaitDatabaseLock("order_core");
                var second=pool.submit(()->admin.withToken("POST","/orders",raw,requestKey,token));
                legacy(second.get(5,TimeUnit.SECONDS),409,"IDEMPOTENCY_IN_PROGRESS","ErrorEnvelope");
                jdbcLock(gate,false);
                HttpResponse<String> created=first.get(15,TimeUnit.SECONDS);legacy(created,201,"OK","OrderEnvelope");
                assertThat(count("order_core")).isEqualTo(1);assertThat(count("order_draft_revision")).isEqualTo(1);assertThat(count("api_idempotency")).isEqualTo(1);
            } finally { jdbcLock(gate,false); }
        } finally { dropPauseTrigger("order_core");pool.shutdownNow(); }
        JsonNode order=data(admin.get("/orders/"+jdbc.queryForObject("SELECT id::text FROM logistics.order_core",String.class)));
        var competing=Executors.newFixedThreadPool(2);
        try(var held=dataSource.getConnection()) {
            held.setAutoCommit(false);try(var lock=held.prepareStatement("SELECT id FROM logistics.order_core WHERE id=? FOR UPDATE")){lock.setObject(1,UUID.fromString(id(order)));lock.executeQuery().close();}
            var first=competing.submit(()->admin.withToken("PUT","/orders/"+id(order),replacement(0,minimal(CUSTOMER_A)).toString(),key(),token));awaitDatabaseLock("order_core");
            var second=competing.submit(()->admin.withToken("PUT","/orders/"+id(order),replacement(0,minimal(CUSTOMER_B)).toString(),key(),token));held.commit();
            List<HttpResponse<String>> results=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200,409);
            for(var response:results)legacy(response,response.statusCode(),response.statusCode()==200?"OK":"VERSION_CONFLICT",response.statusCode()==200?"OrderEnvelope":"ErrorEnvelope");
            assertThat(count("order_draft_revision")).isEqualTo(2);assertThat(count("api_idempotency")).isEqualTo(2);
        }finally{competing.shutdownNow();}
    }

    @Test
    void customerDisableAndRenameWinningTheRowLockAreObservedBeforeTheNewOrderSnapshotCommits() throws Exception {
        String token=admin.csrf();var pool=Executors.newSingleThreadExecutor();
        try(var held=dataSource.getConnection()){
            held.setAutoCommit(false);
            try(var change=held.prepareStatement("UPDATE logistics.crm_customer SET enabled=false,version=version+1 WHERE id=?")){change.setObject(1,UUID.fromString(CUSTOMER_A));change.executeUpdate();}
            var request=pool.submit(()->admin.withToken("POST","/orders",minimal(CUSTOMER_A).toString(),key(),token));awaitDatabaseLock("crm_customer");held.commit();
            legacy(request.get(15,TimeUnit.SECONDS),400,"INVALID_CUSTOMER","ErrorEnvelope");assertThat(count("order_core")).isZero();assertThat(count("api_idempotency")).isZero();
            try(var change=held.prepareStatement("UPDATE logistics.crm_customer SET name='合成鎖後新名稱',version=version+1 WHERE id=?")){change.setObject(1,UUID.fromString(CUSTOMER_B));change.executeUpdate();}
            var renamed=pool.submit(()->admin.withToken("POST","/orders",minimal(CUSTOMER_B).toString(),key(),token));awaitDatabaseLock("crm_customer");held.commit();
            HttpResponse<String> response=renamed.get(15,TimeUnit.SECONDS);legacy(response,201,"OK","OrderEnvelope");assertThat(data(response).get("customerName").asString()).isEqualTo("合成鎖後新名稱");
        }finally{pool.shutdownNow();}
    }

    @Test
    void actualOrderCreationHoldsCustomerSourceUntilCommitAndItsHistoricalCopySurvivesWaitingDisable() throws Exception {
        String token=admin.csrf();createPauseTrigger("order_core");var pool=Executors.newFixedThreadPool(2);
        try(var gate=dataSource.getConnection()){
            jdbcLock(gate,true);
            try{
                var create=pool.submit(()->admin.withToken("POST","/orders",minimal(CUSTOMER_A).toString(),key(),token));awaitDatabaseLock("order_core");
                var disable=pool.submit(()->jdbc.update("UPDATE logistics.crm_customer SET enabled=false,name='合成提交後改名',version=version+1 WHERE id=?",UUID.fromString(CUSTOMER_A)));
                awaitDatabaseLock("crm_customer");assertThat(disable.isDone()).isFalse();jdbcLock(gate,false);
                HttpResponse<String> response=create.get(15,TimeUnit.SECONDS);legacy(response,201,"OK","OrderEnvelope");assertThat(disable.get(15,TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(data(response).get("customerName").asString()).isEqualTo("示意客戶甲");
                assertThat(data(admin.get("/orders/"+id(data(response))))).isEqualTo(data(response));
                assertThat(jdbc.queryForObject("SELECT enabled FROM logistics.crm_customer WHERE id=?",Boolean.class,UUID.fromString(CUSTOMER_A))).isFalse();
            }finally{jdbcLock(gate,false);}
        }finally{dropPauseTrigger("order_core");pool.shutdownNow();}
    }

    @Test
    void canonicalLockRechecksRevokedActorBeforeAnyOrderOrIdempotencyWrites() throws Exception {
        String token=admin.csrf();var pool=Executors.newSingleThreadExecutor();
        try(var held=dataSource.getConnection()){
            held.setAutoCommit(false);try(var lock=held.createStatement()){lock.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();}
            var request=pool.submit(()->admin.withToken("POST","/orders",minimal(CUSTOMER_A).toString(),key(),token));awaitDatabaseLock("upms_lock");
            jdbc.update("UPDATE logistics.upms_account SET role='DRIVER',auth_version=auth_version+1 WHERE id=?",actor.id());held.commit();
            legacy(request.get(15,TimeUnit.SECONDS),401,"UNAUTHENTICATED","ErrorEnvelope");
            assertThat(counts().values()).allMatch(value->value==0);
        }finally{pool.shutdownNow();}
    }

    @Test
    void getAndAcceptanceReadOneCommittedAggregateVersionInsteadOfMixingNewChildrenWithOldVersion() throws Exception {
        for(String suffix:List.of("","/acceptance-check")){
            JsonNode original=create(admin,TomContract.example("graphCases","DEMO-IMPORT-CONTRACT").get("draft"),key());
            String token=admin.csrf();
            var rootRead=new java.util.concurrent.CountDownLatch(1);var resumeRead=new java.util.concurrent.CountDownLatch(1);
            var armed=new java.util.concurrent.atomic.AtomicBoolean(true);
            // The spy only inserts a scheduling barrier after the real root SELECT.
            // Every query and every write still executes against the real PostgreSQL transaction.
            org.mockito.Mockito.doAnswer(invocation->{
                Object rows=invocation.callRealMethod();String sql=invocation.getArgument(0,String.class);
                if(sql.startsWith("SELECT c.id,c.order_no,c.lifecycle_status,t.* FROM logistics.order_core")
                        &&!sql.contains("FOR UPDATE")&&armed.compareAndSet(true,false)){
                    rootRead.countDown();if(!resumeRead.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Read snapshot barrier timed out");
                }
                return rows;
            }).when(jdbc).queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class));
            var reader=Executors.newSingleThreadExecutor();
            try{
                var pending=reader.submit(()->admin.get("/orders/"+id(original)+suffix));
                assertThat(rootRead.await(10,TimeUnit.SECONDS)).as("Read the real root before the competing commit").isTrue();
                HttpResponse<String> written=admin.withToken("PUT","/orders/"+id(original),replacement(0,minimal(CUSTOMER_B)).toString(),key(),token);
                legacy(written,200,"OK","OrderEnvelope");assertThat(data(written).get("version").asLong()).isEqualTo(1);
                assertThat(data(written).get("items").size()).isZero();
                resumeRead.countDown();HttpResponse<String> read=pending.get(15,TimeUnit.SECONDS);
                legacy(read,200,"OK",suffix.isEmpty()?"OrderEnvelope":"AcceptanceCheckEnvelope");
                assertThat(data(read)).isEqualTo(suffix.isEmpty()?original:com.xksystem.logistics.tom.domain.AcceptanceChecker.check(original,true));
                assertThat(data(admin.get("/orders/"+id(original)))).isEqualTo(data(written));
            }finally{
                resumeRead.countDown();reader.shutdown();
                if(!reader.awaitTermination(5,TimeUnit.SECONDS))reader.shutdownNow();
                org.mockito.Mockito.doCallRealMethod().when(jdbc).queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class));
            }
        }
    }

    @Test
    void idempotencyHeaderValidationIsStrictCaseSensitiveAndDoesNotSilentlyChooseADuplicateValue() throws Exception {
        String token=admin.csrf();ObjectNode draft=minimal(CUSTOMER_A);Map<String,Long> before=counts();
        for(String invalid:List.of("", " ", "bad/key", "has space", "x".repeat(129)))
            legacy(admin.withToken("POST","/orders",draft.toString(),invalid,token),400,"VALIDATION_ERROR","ErrorEnvelope");
        legacy(admin.withToken("POST","/orders",draft.toString(),null,token),400,"VALIDATION_ERROR","ErrorEnvelope");
        var duplicate=HttpRequest.newBuilder(URI.create("https://localhost:"+port+"/api/v1/orders")).header("Accept","application/json").header("Content-Type","application/json").header("X-CSRF-TOKEN",token)
                .header("Idempotency-Key","duplicate").header("Idempotency-Key","duplicate").POST(HttpRequest.BodyPublishers.ofString(draft.toString())).build();
        legacy(admin.client.send(duplicate,HttpResponse.BodyHandlers.ofString()),400,"VALIDATION_ERROR","ErrorEnvelope");assertThat(counts()).isEqualTo(before);
        JsonNode upper=create(admin,draft,"A".repeat(128));JsonNode lower=create(admin,draft,"a".repeat(128));assertThat(id(upper)).isNotEqualTo(id(lower));
        ObjectNode graph=simpleGraph();String graphKey=key();create(admin,graph,graphKey);
        ObjectNode reordered=graph.deepCopy();reordered.set("items",json.createArrayNode().add(graph.get("items").get(1)).add(graph.get("items").get(0)));
        legacy(admin.order("POST","/orders",reordered,graphKey,true),409,"IDEMPOTENCY_KEY_REUSED","ErrorEnvelope");
        String unicodeKey=key();create(admin,draft.deepCopy().put("remark","é"),unicodeKey);
        legacy(admin.order("POST","/orders",draft.deepCopy().put("remark","e\u0301"),unicodeKey,true),409,"IDEMPOTENCY_KEY_REUSED","ErrorEnvelope");
        String decimalKey=key();ObjectNode decimal=graph.deepCopy();((ObjectNode)decimal.get("items").get(0)).put("grossWeight","1.0");create(admin,decimal,decimalKey);
        ((ObjectNode)decimal.get("items").get(0)).put("grossWeight","1.00");legacy(admin.order("POST","/orders",decimal,decimalKey,true),409,"IDEMPOTENCY_KEY_REUSED","ErrorEnvelope");
    }

    @Test
    void invalidMediaAndEveryUnsupportedQueryOrMethodUseThePreservedLegacyValidationEnvelope() throws Exception {
        JsonNode order=create(admin,minimal(CUSTOMER_A),key());String token=admin.csrf();
        var request=HttpRequest.newBuilder(URI.create("https://localhost:"+port+"/api/v1/orders")).header("Content-Type","text/plain").header("X-CSRF-TOKEN",token).header("Idempotency-Key",key()).POST(HttpRequest.BodyPublishers.ofString(minimal(CUSTOMER_A).toString())).build();
        legacy(admin.client.send(request,HttpResponse.BodyHandlers.ofString()),400,"VALIDATION_ERROR","ErrorEnvelope");
        for(String path:List.of("/orders/"+id(order)+"?unknown=1","/orders/"+id(order)+"/acceptance-check?unknown=1"))legacy(admin.get(path),400,"VALIDATION_ERROR","ErrorEnvelope");
        legacy(admin.order("PUT","/orders/"+id(order)+"?unknown=1",replacement(0,minimal(CUSTOMER_A)),key(),true),400,"VALIDATION_ERROR","ErrorEnvelope");
        legacy(admin.order("PATCH","/orders/"+id(order),replacement(0,minimal(CUSTOMER_A)),key(),true),400,"VALIDATION_ERROR","ErrorEnvelope");
        assertThat(data(admin.get("/orders/"+id(order)))).isEqualTo(order);
    }

    @Test
    void databaseForeignKeysForbidCrossOrderTaskOwnershipAndServerMaintainsActorAuditFields() throws Exception {
        JsonNode first=create(admin,simpleGraph(),key()), second=create(admin,simpleGraph(),key());
        UUID firstTask=UUID.fromString(first.get("tasks").get(0).get("id").asString()), secondItem=UUID.fromString(second.get("items").get(0).get("id").asString());
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.transport_task SET item_id=? WHERE id=?",secondItem,firstTask)).isInstanceOf(DataIntegrityViolationException.class);
        UUID secondTask=UUID.fromString(second.get("tasks").get(0).get("id").asString());
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.task_dependency SET predecessor_task_id=? WHERE order_id=?",secondTask,UUID.fromString(id(first)))).isInstanceOf(DataIntegrityViolationException.class);
        for(String table:TABLES){
            assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics."+table+" WHERE created_by<>? OR updated_by<>? OR created_at IS NULL OR updated_at IS NULL",Long.class,actor.id(),actor.id())).isZero();
        }
        assertThat(data(admin.get("/orders/"+id(first)))).isEqualTo(first);
        Account dispatcherAccount=account("DISPATCHER",false);Browser dispatcher=new Browser();login(dispatcher,dispatcherAccount);
        JsonNode edited=replace(dispatcher,id(first),0,simpleGraph(),key());
        assertThat(edited.get("createdBy").asString()).isEqualTo(actor.id().toString());assertThat(edited.get("updatedBy").asString()).isEqualTo(dispatcherAccount.id().toString());
        for(String table:List.of("cargo_item","transport_task"))assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics."+table+" WHERE order_id=? AND (created_by<>? OR updated_by<>?)",Long.class,UUID.fromString(id(first)),actor.id(),dispatcherAccount.id())).isZero();
    }


    @Test
    void databaseUnavailabilityInTheAuthenticationFilterKeepsTheOriginalSanitizedOrderErrorEnvelope() throws Exception {
        JsonNode original=create(admin,minimal(CUSTOMER_A),key());String token=admin.csrf();
        String administrativeUrl=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/postgres");
        try(var connection=DriverManager.getConnection(administrativeUrl,postgres.getUsername(),postgres.getPassword());var statement=connection.createStatement()){
            statement.execute("ALTER DATABASE test WITH ALLOW_CONNECTIONS false");
            try{
                statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='test'");
                legacy(admin.get("/orders/"+id(original)),500,"INTERNAL_ERROR","ErrorEnvelope");
                legacy(admin.withToken("POST","/orders",minimal(CUSTOMER_A).toString(),key(),token),500,"INTERNAL_ERROR","ErrorEnvelope");
            }finally{statement.execute("ALTER DATABASE test WITH ALLOW_CONNECTIONS true");}
        }
        RuntimeException last=null;
        for(int attempt=0;attempt<5;attempt++)try{assertThat(jdbc.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);return;}catch(org.springframework.dao.DataAccessException failure){last=failure;}
        throw new IllegalStateException("Isolated TOM database did not recover",last);
    }

    private void createPauseTrigger(String table){
        jdbc.execute("CREATE FUNCTION logistics.test_tom_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202604,42); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_tom_pause BEFORE INSERT ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_tom_pause()");
    }
    private void dropPauseTrigger(String table){jdbc.execute("DROP TRIGGER test_tom_pause ON logistics."+table);jdbc.execute("DROP FUNCTION logistics.test_tom_pause()");}
    private void jdbcLock(java.sql.Connection connection,boolean acquire)throws java.sql.SQLException{try(var statement=connection.createStatement()){statement.execute("SELECT pg_advisory_"+(acquire?"lock":"unlock")+"(202604,42)");}}
    private void awaitDatabaseLock(String table)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        try(var observer=dataSource.getConnection();var statement=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")){
            assertThat(observer.getAutoCommit()).isTrue();statement.setString(1,"%"+table+"%");
            while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getLong(1)>0)return;}Thread.sleep(20);}
        }
        throw new AssertionError("Expected real database lock wait for "+table);
    }


    private ObjectNode minimal(String customer) { return json.createObjectNode().put("type", "LOCAL").put("customerId", customer); }
    private ObjectNode replacement(long version, JsonNode draft) { return json.createObjectNode().put("expectedVersion", version).set("draft", draft); }
    private ObjectNode simpleGraph() {
        ObjectNode draft = minimal(CUSTOMER_C);
        draft.set("items", json.createArrayNode().add(json.createObjectNode().put("clientRef", "item-a").put("sequence", 1)).add(json.createObjectNode().put("clientRef", "item-b").put("sequence", 2)));
        draft.set("tasks", json.createArrayNode().add(json.createObjectNode().put("clientRef", "task-a").put("itemRef", "item-a"))
                .add(json.createObjectNode().put("clientRef", "task-b").put("itemRef", "item-b").set("predecessorRefs", json.createArrayNode().add("task-a"))));
        return draft;
    }
    private Map<String, JsonNode> byRef(JsonNode values) { Map<String, JsonNode> result = new LinkedHashMap<>(); for (JsonNode value : values) result.put(value.get("clientRef").asString(), value); return result; }
    private JsonNode create(Browser browser, JsonNode draft, String requestKey) throws Exception { HttpResponse<String> response = browser.order("POST", "/orders", draft, requestKey, true); legacy(response, 201, "OK", "OrderEnvelope"); return data(response); }
    private JsonNode replace(Browser browser, String id, long version, JsonNode draft, String requestKey) throws Exception { HttpResponse<String> response = browser.order("PUT", "/orders/" + id, replacement(version, draft), requestKey, true); legacy(response, 200, "OK", "OrderEnvelope"); return data(response); }
    private JsonNode check(Browser browser, String id) throws Exception { HttpResponse<String> response = browser.get("/orders/" + id + "/acceptance-check"); legacy(response, 200, "OK", "AcceptanceCheckEnvelope"); return data(response); }
    private String id(JsonNode order) { return order.get("id").asString(); }
    private String key() { return "test:" + UUID.randomUUID(); }
    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    private JsonNode data(HttpResponse<String> response) { return body(response).get("data"); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM logistics." + table, Long.class); }
    private Map<String, Long> counts() { Map<String, Long> counts = new LinkedHashMap<>(); TABLES.forEach(table -> counts.put(table, count(table))); return counts; }
    private void legacy(HttpResponse<String> response, int status, String code, String schema) {
        JsonNode body = body(response);
        assertThat(response.statusCode()).as("Legacy TOM HTTP status (%s / %s)",body.path("code").asString(),body.path("message").asString()).isEqualTo(status);
        assertThat(body.get("code").asString()).isEqualTo(code); TomContract.assertSchema(schema, body);
        assertThat(body.has("timestamp")).isFalse();
    }
    private void upms(HttpResponse<String> response, int status) { assertThat(response.statusCode()).as("UPMS HTTP status (%s / %s)",body(response).path("code").asString(),body(response).path("message").asString()).isEqualTo(status); assertThat(body(response).get("code").asString()).isEqualTo("0000"); }
    private Account account(String role, boolean temporary) throws Exception {
        String username = "tom-test-" + UUID.randomUUID().toString().substring(0, 16); String password = UpmsTestData.password();
        try (var connection = dataSource.getConnection()) { return new Account(UpmsTestData.insert(connection, encoder, username, role, password, temporary, clock.instant()), username, password); }
    }
    private void login(Browser browser, Account account) throws Exception { upms(browser.raw("POST", "/auth/login", json.writeValueAsString(Map.of("username", account.username(), "password", account.password())), null, true), 200); }
    private record Account(UUID id, String username, String password) {}
    /** Sends only the historical name; actual Tomcat session resolution must bridge it. */
    private final class AliasBrowser {
        private final Map<String,String> jar=new LinkedHashMap<>();
        private final HttpClient client=HttpClient.newBuilder().sslContext(TlsTestSupport.trustedContext()).connectTimeout(Duration.ofSeconds(10)).build();
        String csrf()throws Exception{HttpResponse<String> response=send("GET","/auth/csrf",null,null,null,null);upms(response,200);return data(response).get("token").asString();}
        HttpResponse<String> send(String method,String path,String payload,String token,String requestKey,String forcedCookies)throws Exception{
            var request=HttpRequest.newBuilder(URI.create("https://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30)).header("Accept","application/json");
            String cookie=forcedCookies!=null?forcedCookies:jar.containsKey("JSESSIONID")?"JSESSIONID="+jar.get("JSESSIONID"):"";
            if(!cookie.isEmpty())request.header("Cookie",cookie);if(token!=null)request.header("X-CSRF-TOKEN",token);if(requestKey!=null)request.header("Idempotency-Key",requestKey);
            if(payload!=null)request.header("Content-Type","application/json");request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(payload));
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            for(String header:response.headers().allValues("Set-Cookie"))for(var parsed:java.net.HttpCookie.parse(header)){if(parsed.getMaxAge()==0)jar.remove(parsed.getName());else jar.put(parsed.getName(),parsed.getValue());}
            return response;
        }
    }
    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).sslContext(TlsTestSupport.trustedContext()).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return raw("GET", path, null, null, false); }
        String csrf() throws Exception { HttpResponse<String> response = raw("GET", "/auth/csrf", null, null, false); upms(response, 200); return data(response).get("token").asString(); }
        HttpResponse<String> order(String method, String path, JsonNode body, String key, boolean includeCsrf) throws Exception { return raw(method, path, body == null ? null : json.writeValueAsString(body), key, includeCsrf); }
        HttpResponse<String> raw(String method, String path, String body, String key, boolean includeCsrf) throws Exception {
            String token = includeCsrf && !method.equals("GET") ? csrf() : null;
            return withToken(method,path,body,key,token);
        }
        HttpResponse<String> withToken(String method,String path,String body,String key,String token)throws Exception {
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
            if (token != null) request.header("X-CSRF-TOKEN", token); if (key != null) request.header("Idempotency-Key", key); if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
