package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.fms.VehicleQueries;
import com.xksystem.logistics.support.MutableTestClock;
import com.xksystem.logistics.support.SyntheticDispatchPolicy;
import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.TomContract;
import com.xksystem.logistics.support.UpmsTestData;
import com.xksystem.logistics.tom.TaskQueries;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Driver-only execution through the actual TLS/security chain and PostgreSQL transaction boundaries. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExecutionIT.Configuration.class)
class ExecutionIT {
    private static final List<String> MY_FIELDS = List.of("assignmentId", "taskId", "taskNo", "item", "taskKind",
            "pickupName", "pickupAddress", "deliveryName", "deliveryAddress", "siteContact", "plannedStartAt",
            "plannedEndAt", "assignedAt", "policyMode");
    private static final List<String> EXECUTION_TABLES = List.of("tms_execution", "tms_execution_event", "tms_execution_command");
    private static final List<String> DISPATCH_TABLES = List.of("tms_task_dispatch", "tms_assignment", "tms_resource_reservation", "tms_dispatch_event", "tms_idempotency");
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres")).withStartupAttempts(3).withDatabaseName("xk_logistics_e2e");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        TlsTestSupport.register(registry);
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary MutableTestClock executionClock() { return new MutableTestClock(); }
        @Bean @Primary TogglePolicy executionPolicy(DataSource dataSource) throws Exception {
            return new TogglePolicy(SyntheticDispatchPolicy.create(dataSource));
        }
    }
    /** Only the mode is mutable in this test. All source qualification delegates to the existing DB-gated fixture. */
    static final class TogglePolicy implements DispatchEligibilityPolicy {
        private final DispatchEligibilityPolicy delegate;
        private volatile PolicyMode mode = PolicyMode.SYNTHETIC_TEST_ONLY;
        TogglePolicy(DispatchEligibilityPolicy delegate) { this.delegate = delegate; }
        @Override public PolicyMode mode() { return mode; }
        @Override public boolean driverApproved(DriverQueries.Snapshot driver) { return delegate.driverApproved(driver); }
        @Override public boolean vehicleApproved(VehicleQueries.Snapshot vehicle, ResourcePosition position) { return delegate.vehicleApproved(vehicle, position); }
        @Override public boolean combinationApproved(TaskQueries.Source source, DriverQueries.Snapshot driver, VehicleQueries.Snapshot main, VehicleQueries.Snapshot trailer) {
            return delegate.combinationApproved(source, driver, main, trailer);
        }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    @Autowired TogglePolicy policy;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Browser> browsers = new ArrayList<>();
    private Account administrator, driverAccount, otherAccount;
    private Browser admin, driverBrowser, otherBrowser;
    private JsonNode driver, otherDriver, main, otherMain;

    @BeforeEach void isolatedRealSourcesAndNormalCurrentDriverBindings() throws Exception {
        jdbc.execute("TRUNCATE TABLE logistics.tom_order_closure_command,logistics.tom_order_closure_task,logistics.tom_order_closure,logistics.tms_completion_confirmation_command,logistics.tms_completion_confirmation,logistics.tms_completion_command,logistics.tms_completion,logistics.tms_exception_event_evidence,logistics.tms_exception_supplement_evidence,logistics.tms_exception_command,logistics.tms_exception_event,logistics.tms_exception_supplement_request,logistics.tms_exception,logistics.tms_execution_evidence_command,logistics.tms_execution_event_evidence,logistics.tms_execution_evidence,logistics.tms_execution_evidence_object,logistics.tms_execution_command,logistics.tms_execution_event,logistics.tms_execution,"
                + "logistics.tms_idempotency,logistics.tms_dispatch_event,logistics.tms_resource_reservation,logistics.tms_assignment,logistics.tms_task_dispatch,"
                + "logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle,logistics.order_core,logistics.tom_order,"
                + "logistics.cargo_item,logistics.transport_task,logistics.task_dependency,logistics.order_draft_revision,logistics.api_idempotency,"
                + "logistics.crm_contact,logistics.crm_address,logistics.crm_customer,logistics.adm_change_log,logistics.upms_audit,logistics.upms_account");
        jdbc.update("DELETE FROM logistics.adm_dict_item WHERE NOT read_only");
        jdbc.update("UPDATE logistics.adm_dict_category SET enabled=true WHERE NOT read_only");
        clock.reset(); policy.mode = PolicyMode.SYNTHETIC_TEST_ONLY;
        administrator = account("ADMIN", false); driverAccount = account("DRIVER", false); otherAccount = account("DRIVER", false);
        admin = login(administrator); driverBrowser = login(driverAccount); otherBrowser = login(otherAccount);
        UUID type = dictionaryType();
        driver = driver(driverAccount, 1); otherDriver = driver(otherAccount, 2);
        main = vehicle(type); otherMain = vehicle(type);
    }
    @AfterEach void stopOnlyThisTestsClients() throws Exception {
        for (Browser browser : browsers) browser.client.shutdownNow();
        for (Browser browser : browsers) assertThat(browser.client.awaitTermination(Duration.ofSeconds(5))).isTrue();
        browsers.clear();
    }

    @Test void detailIsMinimalCurrentProjectionAndWaitingReadDoesNotInventExecutionFacts() throws Exception {
        JsonNode assigned = assigned("LOCAL", driver, main);
        Map<String, String> before = allDigest();
        JsonNode listed = company(driverBrowser.get("/tms/my-tasks"), 200, "0000").get("content").get(0);
        shape(listed, MY_FIELDS);
        JsonNode detail = company(driverBrowser.get(ownPath(assigned)), 200, "0000");
        var detailFields = new ArrayList<>(MY_FIELDS); detailFields.add("execution"); shape(detail, detailFields);
        var summary = (ObjectNode) detail.deepCopy(); summary.remove("execution");
        assertThat(summary).isEqualTo(listed);
        waiting(detail.get("execution"));
        assertThat(detail.get("pickupAddress").asString()).isEqualTo(" 合成\u0000出發地址 e\u0301 ");
        assertThat(detail.get("item").get("grossWeight").asString()).isEqualTo("0.0000000000000000001");
        assertThat(detail.get("plannedStartAt").asString()).isEqualTo("1500-01-02T00:00:00.123456+23:59");
        assertThat(detail.get("siteContact").isObject()).isTrue();
        assertThat(detail.get("siteContact").get("name").isNull()).isTrue();
        assertThat(allDigest()).isEqualTo(before); noExecution();
    }

    @ParameterizedTest @ValueSource(strings={"IMPORT", "EXPORT", "LOCAL"})
    void departureCreatesOneIndependentServerAuditedTransitionAndNoDispatchOrSourceMutation(String type) throws Exception {
        JsonNode assigned = assigned(type, driver, main);
        Map<String, String> before = dispatchAndSourceDigest();
        clock.advance(Duration.ofNanos(123456789));
        Instant expected = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var reply = driverBrowser.write("POST", departPath(assigned), fields("expectedVersion", 0), key());
        JsonNode result = company(reply, 200, "0000");
        assertThat(reply.headers().firstValue("Idempotency-Replayed")).contains("false");
        departureResult(result, assigned, expected);
        assertThat(dispatchAndSourceDigest()).isEqualTo(before);
        assertThat(count("tms_execution")).isEqualTo(1);
        assertThat(count("tms_execution_event")).isEqualTo(1);
        assertThat(count("tms_execution_command")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT phase FROM logistics.tms_execution WHERE assignment_id=?", String.class, uuid(assigned))).isEqualTo("DEPARTED");
        assertThat(jdbc.queryForObject("SELECT version FROM logistics.tms_execution WHERE assignment_id=?", Long.class, uuid(assigned))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT departed_at FROM logistics.tms_execution WHERE assignment_id=?", OffsetDateTime.class, uuid(assigned)).toInstant()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.tms_execution_event WHERE assignment_id=? AND actor_id=? AND from_phase='WAITING' AND to_phase='DEPARTED' AND from_version=0 AND to_version=1 AND occurred_at=?",
                Long.class, uuid(assigned), driverAccount.id(), OffsetDateTime.ofInstant(expected, ZoneOffset.UTC))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.tms_execution_command WHERE assignment_id=? AND actor_id=? AND driver_id=? AND original_status=200 AND company_scope='SINGLE_COMPANY' AND http_method='POST'",
                Long.class, uuid(assigned), driverAccount.id(), uuid(driver))).isEqualTo(1);
        JsonNode current = company(driverBrowser.get(ownPath(assigned)), 200, "0000");
        assertThat(current.get("execution")).isEqualTo(result.get("execution"));
        shape(company(driverBrowser.get("/tms/my-tasks"), 200, "0000").get("content").get(0), MY_FIELDS);
    }

    @Test void strictShapeRawUtf8QueryHeadersAndProtocolRefuseWithoutAnyExecutionWrites() throws Exception {
        JsonNode assigned = assigned("LOCAL", driver, main); String path = departPath(assigned);
        Map<String, String> before = allDigest(); String token = driverBrowser.csrf();
        for (String payload : List.of("null", "[]", "{}", "{\"expectedVersion\":null}", "{\"expectedVersion\":\"0\"}",
                "{\"expectedVersion\":true}", "{\"expectedVersion\":-1}", "{\"expectedVersion\":0.1}",
                "{\"expectedVersion\":9007199254740992}", "{\"expectedVersion\":0,\"expectedVersion\":0}",
                "{\"expectedVersion\":0} {}", "{\"expectedVersion\":0,\"actorId\":\"x\"}",
                "{\"expectedVersion\":0,\"phase\":\"COMPLETED\"}", "{\"expectedVersion\":0,\"timestamp\":\"now\"}",
                "{\"expectedVersion\":0,\"\\ud800\":true}")) {
            company(driverBrowser.send("POST", path, payload, key(), token), 400, "E0400");
        }
        for (String query : List.of("driverId="+id(otherDriver), "accountId="+otherAccount.id(), "taskId="+assigned.get("taskId").asString(),
                "historyPage=0", "unknown=true", "x=1&x=2")) {
            company(driverBrowser.get(ownPath(assigned)+"?"+query), 400, "E0400");
            company(driverBrowser.send("POST", path+"?"+query, "{\"expectedVersion\":0}", key(), token), 400, "E0400");
        }
        for (String value : List.of("not-a-uuid", "1-1-1-1-1")) company(driverBrowser.get("/tms/my-tasks/"+value), 400, "E0400");
        company(driverBrowser.send("POST", path, "{\"expectedVersion\":0}", null, token), 400, "E0400");
        company(driverBrowser.send("POST", path, "{\"expectedVersion\":0}", "invalid key", token), 400, "E0400");
        var duplicate = driverBrowser.request("POST", path, "{\"expectedVersion\":0}".getBytes(StandardCharsets.UTF_8), "application/json", token)
                .header("Idempotency-Key", key()).header("Idempotency-Key", key()).build();
        company(driverBrowser.client.send(duplicate, HttpResponse.BodyHandlers.ofString()), 400, "E0400");
        var malformedUtf8 = driverBrowser.request("POST", path, new byte[]{'{','"','x','"',':','"',(byte)0xc3,(byte)0x28,'"','}'}, "application/json", token)
                .header("Idempotency-Key", key()).build();
        company(driverBrowser.client.send(malformedUtf8, HttpResponse.BodyHandlers.ofString()), 400, "E0400");
        company(driverBrowser.send("PUT", path, "{\"expectedVersion\":0}", key(), token), 405, "E0405");
        company(driverBrowser.send("POST", ownPath(assigned), "{\"expectedVersion\":0}", key(), token), 405, "E0405");
        var nonJson = driverBrowser.request("POST", path, "{\"expectedVersion\":0}".getBytes(StandardCharsets.UTF_8), "text/plain", token).header("Idempotency-Key", key()).build();
        company(driverBrowser.client.send(nonJson, HttpResponse.BodyHandlers.ofString()), 415, "E0415");
        assertThat(allDigest()).isEqualTo(before); noExecution();
    }

    @ParameterizedTest @ValueSource(strings={"0.0", "0e4", "-0"})
    void mathematicalZeroIntegersShareCanonicalRequestIdentity(String literal) throws Exception {
        JsonNode assigned = assigned("LOCAL", driver, main); String commandKey = key();
        JsonNode result = company(driverBrowser.send("POST", departPath(assigned), "{\"expectedVersion\":"+literal+"}", commandKey, driverBrowser.csrf()), 200, "0000");
        Map<String, String> after = allDigest();
        var replay = driverBrowser.write("POST", departPath(assigned), fields("expectedVersion", 0), commandKey);
        assertThat(company(replay, 200, "0000")).isEqualTo(result);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(allDigest()).isEqualTo(after);
    }

    @Test void onlyNormalCurrentDriverCanReadOrDepartAndSecurityUsesCompanyErrorShape() throws Exception {
        JsonNode assigned = assigned("LOCAL", driver, main); Browser anonymous = browser();
        company(anonymous.get(ownPath(assigned)), 401, "E0401");
        company(anonymous.send("POST", departPath(assigned), "{\"expectedVersion\":0}", key(), null), 401, "E0401");
        for (Browser manager : List.of(admin, login(account("DISPATCHER", false)))) {
            company(manager.get(ownPath(assigned)), 403, "E0403");
            company(manager.write("POST", departPath(assigned), fields("expectedVersion",0), key()), 403, "E0403");
            // Only the original list GET is a DRIVER route; a broad prefix must not steal its method fallback.
            company(manager.write("POST", "/tms/my-tasks", fields(), key()), 405, "E0405");
        }
        company(driverBrowser.write("POST", "/tms/my-tasks", fields(), key()), 403, "E0403");
        Browser temporary = login(account("DRIVER", true));
        company(temporary.get(ownPath(assigned)), 403, "E0403");
        company(temporary.write("POST", departPath(assigned), fields("expectedVersion",0), key()), 403, "E0403");
        JsonNode csrf = company(driverBrowser.send("POST", departPath(assigned), "{\"expectedVersion\":0}", key(), null), 403, "E0403");
        assertThat(csrf.get("reason").asString()).isEqualTo("CSRF_INVALID"); noExecution();
    }

    @Test void anotherPersonsOrMissingAssignmentIsOpaqueForBothDetailAndCommand() throws Exception {
        JsonNode assigned = assigned("LOCAL", driver, main); Map<String, String> before = allDigest();
        for (String selected : List.of(id(assigned), key())) {
            assertThat(company(otherBrowser.get("/tms/my-tasks/"+selected), 404, "E0404").isNull()).isTrue();
            assertThat(company(otherBrowser.write("POST", "/tms/my-tasks/"+selected+"/depart", fields("expectedVersion",0), key()), 404, "E0404").isNull()).isTrue();
        }
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void successfulReceiptRequiresCurrentBindingAndCannotFollowAccountToAnotherDriver() throws Exception {
        JsonNode assigned = assigned("LOCAL", driver, main); String commandKey=key();
        company(driverBrowser.write("POST", departPath(assigned), fields("expectedVersion",0), commandKey),200,"0000");
        company(admin.write("PATCH", "/fms/drivers/"+id(driver), fields("version",0,"accountId",null),null),200,"0000");
        company(admin.write("PATCH", "/fms/drivers/"+id(otherDriver), fields("version",0,"accountId",driverAccount.id().toString()),null),200,"0000");
        Map<String,String> before=allDigest();
        assertThat(company(driverBrowser.get(ownPath(assigned)),404,"E0404").isNull()).isTrue();
        assertThat(company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),404,"E0404").isNull()).isTrue();
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void receiptAuthorizationDoesNotSurviveRoleRevocation() throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main); String commandKey=key();
        company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),200,"0000");
        String token=driverBrowser.csrf(); Map<String,String> before=allDigest();
        jdbc.update("UPDATE logistics.upms_account SET auth_version=auth_version+1,role='DISPATCHER' WHERE id=?",driverAccount.id());
        company(driverBrowser.send("POST",departPath(assigned),"{\"expectedVersion\":0}",commandKey,token),401,"E0401");
        company(driverBrowser.get(ownPath(assigned)),401,"E0401");
        Browser changedRole=login(driverAccount);
        company(changedRole.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),403,"E0403");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void logoutOrDisabledAccountCannotReadOrReplayItsFormerExecutionReceipt() throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main);String commandKey=key();
        company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),200,"0000");
        company(driverBrowser.write("POST","/auth/logout",fields(),null),200,"0000");
        Map<String,String> before=allDigest();
        company(driverBrowser.get(ownPath(assigned)),401,"E0401");
        company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),401,"E0401");
        Browser loggedInAgain=login(driverAccount);String token=loggedInAgain.csrf();
        jdbc.update("UPDATE logistics.upms_account SET enabled=false,auth_version=auth_version+1 WHERE id=?",driverAccount.id());
        company(loggedInAgain.send("POST",departPath(assigned),"{\"expectedVersion\":0}",commandKey,token),401,"E0401");
        company(loggedInAgain.get(ownPath(assigned)),401,"E0401");
        assertThat(allDigest()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"VERSION","CLOSED","OCCUPATION"})
    void invalidCurrentSourceOrCompleteOccupationIsOpaqueEvenBeforeHistoricalReplay(String change) throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main); String commandKey=key();
        company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),200,"0000");
        switch(change) {
            case "VERSION" -> jdbc.update("UPDATE logistics.tom_order SET version=version+1 WHERE order_id=?",UUID.fromString(assigned.get("orderId").asString()));
            case "CLOSED" -> jdbc.update("UPDATE logistics.order_core SET lifecycle_status='CLOSED' WHERE id=?",UUID.fromString(assigned.get("orderId").asString()));
            default -> jdbc.update("UPDATE logistics.tms_resource_reservation SET released_at=CURRENT_TIMESTAMP,release_reason='合成占用缺失' WHERE assignment_id=? AND position='MAIN'",uuid(assigned));
        }
        Map<String,String> before=allDigest();
        assertThat(company(driverBrowser.get(ownPath(assigned)),404,"E0404").isNull()).isTrue();
        assertThat(company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),404,"E0404").isNull()).isTrue();
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void originalReceiptSurvivesCurrentDepartedVersionAndPolicyButNewCommandsDoNotRepeatTransition() throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main); String commandKey=key();
        JsonNode result=company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),200,"0000");
        Map<String,String> before=allDigest(); policy.mode=PolicyMode.UNCONFIRMED;
        var replay=driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey);
        assertThat(company(replay,200,"0000")).isEqualTo(result);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(company(driverBrowser.get(ownPath(assigned)),200,"0000").get("execution")).isEqualTo(result.get("execution"));
        JsonNode version=business(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),key()),"E1701","VERSION_CONFLICT");
        assertThat(version.propertyNames()).containsExactlyInAnyOrder("reason","actualVersion"); assertThat(version.get("actualVersion").asLong()).isEqualTo(1);
        business(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",1),key()),"E1702","INVALID_STATE");
        business(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",1),commandKey),"E1704","IDEMPOTENCY_KEY_REUSED");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void sameKeyProcessingIsBoundedBeforeCanonicalWriteQueueAndOnlyOneReceiptCommits() throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main); String commandKey=key(),token=driverBrowser.csrf();
        Browser anotherSession=login(driverAccount); String anotherToken=anotherSession.csrf();
        pause("tms_execution","BEFORE INSERT");
        try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()) {
            advisory(held,true);
            var first=pool.submit(()->driverBrowser.send("POST",departPath(assigned),"{\"expectedVersion\":0}",commandKey,token));
            try {
                awaitLock("tms_execution");
                long start=System.nanoTime();
                business(anotherSession.send("POST",departPath(assigned),"{\"expectedVersion\":0}",commandKey,anotherToken),"E1705","IDEMPOTENCY_IN_PROGRESS");
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(15));
            } finally { advisory(held,false); }
            company(first.get(20,TimeUnit.SECONDS),200,"0000");
        } finally { unpause("tms_execution"); }
        assertThat(count("tms_execution_event")).isEqualTo(1); assertThat(count("tms_execution_command")).isEqualTo(1);
    }

    @Test void competingNewDepartureKeysShareAssignmentLockAndOnlyOneVersionWins() throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main); Browser another=login(driverAccount);
        String firstToken=driverBrowser.csrf(),secondToken=another.csrf();
        List<HttpResponse<String>> results;
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()) {
            held.setAutoCommit(false);canonical(held);
            var first=pool.submit(()->driverBrowser.send("POST",departPath(assigned),"{\"expectedVersion\":0}",key(),firstToken));
            var second=pool.submit(()->another.send("POST",departPath(assigned),"{\"expectedVersion\":0}",key(),secondToken));
            try { awaitLockCount("upms_lock",2); } finally { held.rollback(); }
            results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
        assertThat(results.stream().filter(value->value.statusCode()==200).count()).isEqualTo(1);
        for(var result:results) if(result.statusCode()==200)company(result,200,"0000");else business(result,"E1701","VERSION_CONFLICT");
        assertThat(count("tms_execution")).isEqualTo(1);assertThat(count("tms_execution_event")).isEqualTo(1);assertThat(count("tms_execution_command")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"revoke","reassign"})
    void departedTransactionWinsAgainstNewManagerWithdrawalWithoutReleasingOrExchangingResources(String action) throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main); Map<String,String> before=dispatchAndSourceDigest();
        String driverToken=driverBrowser.csrf(),adminToken=admin.csrf();pause("tms_execution","BEFORE INSERT");
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()) {
            advisory(held,true);
            var departure=pool.submit(()->driverBrowser.send("POST",departPath(assigned),"{\"expectedVersion\":0}",key(),driverToken));
            try {
                awaitLock("tms_execution");
                var manager=pool.submit(()->admin.send("POST",managerPath(assigned,action),json.writeValueAsString(managerBody(action)),key(),adminToken));
                awaitLock("upms_lock");advisory(held,false);
                company(departure.get(20,TimeUnit.SECONDS),200,"0000");business(manager.get(20,TimeUnit.SECONDS),"E1603","INVALID_STATE");
            } finally { advisory(held,false); }
        } finally { unpause("tms_execution"); }
        assertThat(dispatchAndSourceDigest()).isEqualTo(before);assertThat(count("tms_execution_event")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"revoke","reassign"})
    void managerWithdrawalWinsAndOldDriverPathNeverRetargetsReplacement(String action) throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main);String driverToken=driverBrowser.csrf(),adminToken=admin.csrf();
        pause("tms_assignment","BEFORE UPDATE");
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()) {
            advisory(held,true);
            var manager=pool.submit(()->admin.send("POST",managerPath(assigned,action),json.writeValueAsString(managerBody(action)),key(),adminToken));
            try {
                awaitLock("tms_assignment");
                var departure=pool.submit(()->driverBrowser.send("POST",departPath(assigned),"{\"expectedVersion\":0}",key(),driverToken));
                awaitLock("upms_lock");advisory(held,false);
                company(manager.get(20,TimeUnit.SECONDS),200,"0000");assertThat(company(departure.get(20,TimeUnit.SECONDS),404,"E0404").isNull()).isTrue();
            } finally { advisory(held,false); }
        } finally { unpause("tms_assignment"); }
        company(driverBrowser.get(ownPath(assigned)),404,"E0404");noExecution();
        assertThat(jdbc.queryForObject("SELECT generation FROM logistics.tms_task_dispatch WHERE task_id=?",Long.class,UUID.fromString(assigned.get("taskId").asString()))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL",Long.class)).isEqualTo(action.equals("revoke")?0:2);
    }

    @ParameterizedTest @ValueSource(strings={"BINDING","AUTH_VERSION"})
    void queuedDepartureRechecksFreshActorAndBindingUnderTheSharedCanonicalLock(String change) throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main); String token=driverBrowser.csrf();
        try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()) {
            held.setAutoCommit(false);canonical(held);
            var result=pool.submit(()->driverBrowser.send("POST",departPath(assigned),"{\"expectedVersion\":0}",key(),token));
            try {
                awaitLock("upms_lock");
                String sql=change.equals("BINDING")?"UPDATE logistics.fms_driver SET account_id=NULL,version=version+1 WHERE id=?"
                        :"UPDATE logistics.upms_account SET auth_version=auth_version+1 WHERE id=?";
                try(var statement=held.prepareStatement(sql)) {
                    statement.setObject(1,change.equals("BINDING")?uuid(driver):driverAccount.id());statement.executeUpdate();
                }
                held.commit();company(result.get(20,TimeUnit.SECONDS),change.equals("BINDING")?404:401,change.equals("BINDING")?"E0404":"E0401");
            } finally { held.rollback(); }
        }
        noExecution();
    }

    @ParameterizedTest @ValueSource(strings={"DETAIL","DEPART","REPLAY"})
    void logoutAheadOfQueuedDriverOperationInvalidatesTheCapturedSessionBeforeAnyDetailOrReceipt(String operation) throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main);String commandKey=key();
        if(operation.equals("REPLAY"))
            company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),200,"0000");
        String token=driverBrowser.csrf();Map<String,String> before=allDigest();
        List<HttpResponse<String>> results;
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()) {
            held.setAutoCommit(false);canonical(held);
            var loggingOut=pool.submit(()->driverBrowser.send("POST","/auth/logout","{}",null,token));
            try {
                // Observe the real row-lock queue: logout is first, while the original session is still valid.
                awaitLockCount("upms_lock",1);
                var pending=pool.submit(()->operation.equals("DETAIL")
                        ?driverBrowser.send("GET",ownPath(assigned),null,null,null)
                        :driverBrowser.send("POST",departPath(assigned),"{\"expectedVersion\":0}",commandKey,token));
                try { awaitLockCount("upms_lock",2); }
                finally { held.rollback(); }
                results=List.of(loggingOut.get(20,TimeUnit.SECONDS),pending.get(20,TimeUnit.SECONDS));
            } finally { held.rollback(); }
        }
        company(results.get(0),200,"0000");
        assertThat(company(results.get(1),401,"E0401").isNull()).isTrue();
        assertThat(results.get(1).headers().firstValue("Idempotency-Replayed")).isEmpty();
        company(driverBrowser.get(ownPath(assigned)),401,"E0401");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void departureAheadOfLogoutCompletesOnceBeforeThatSessionIsInvalidated() throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main);String token=driverBrowser.csrf();
        Map<String,String> sourceBefore=dispatchAndSourceDigest();pause("tms_execution","BEFORE INSERT");
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()) {
            advisory(held,true);
            var departure=pool.submit(()->driverBrowser.send("POST",departPath(assigned),"{\"expectedVersion\":0}",key(),token));
            try {
                awaitLock("tms_execution");
                var loggingOut=pool.submit(()->driverBrowser.send("POST","/auth/logout","{}",null,token));
                awaitLock("upms_lock");advisory(held,false);
                company(departure.get(20,TimeUnit.SECONDS),200,"0000");
                company(loggingOut.get(20,TimeUnit.SECONDS),200,"0000");
            } finally { advisory(held,false); }
        } finally { unpause("tms_execution"); }
        company(driverBrowser.get(ownPath(assigned)),401,"E0401");
        assertThat(count("tms_execution")).isEqualTo(1);assertThat(count("tms_execution_event")).isEqualTo(1);
        assertThat(count("tms_execution_command")).isEqualTo(1);assertThat(dispatchAndSourceDigest()).isEqualTo(sourceBefore);
    }

    @ParameterizedTest @ValueSource(strings={"ASSIGN","REASSIGN"})
    void originalSuccessfulManagerReceiptStillReplaysAfterItsCurrentDriverDeparts(String action) throws Exception {
        JsonNode order=acceptedOrder("LOCAL");UUID task=null;
        for(JsonNode candidate:order.get("tasks"))if(candidate.get("predecessorRefs").isEmpty())task=uuid(candidate);
        assertThat(task).isNotNull();
        String managerKey=key(),managerPath="/tms/tasks/"+task+"/dispatch";
        Map<String,Object> managerRequest=fields("expectedGeneration",0,"driverId",id(driver),"mainVehicleId",id(main),"trailerId",null);
        JsonNode originalReceipt=company(admin.write("POST",managerPath,managerRequest,managerKey),200,"0000");
        Browser assignedDriver=driverBrowser;
        if(action.equals("REASSIGN")) {
            managerKey=key();managerPath=managerPath(originalReceipt.get("assignment"),"reassign");
            managerRequest=managerBody("reassign");
            originalReceipt=company(admin.write("POST",managerPath,managerRequest,managerKey),200,"0000");
            assignedDriver=otherBrowser;
        }
        company(assignedDriver.write("POST",departPath(originalReceipt.get("assignment")),fields("expectedVersion",0),key()),200,"0000");
        Map<String,String> afterDeparture=allDigest();
        var replay=admin.write("POST",managerPath,managerRequest,managerKey);
        assertThat(company(replay,200,"0000")).isEqualTo(originalReceipt);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(allDigest()).isEqualTo(afterDeparture);
        assertThat(count("tms_execution")).isEqualTo(1);assertThat(count("tms_execution_event")).isEqualTo(1);
        assertThat(count("tms_execution_command")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"tms_execution","tms_execution_event","tms_execution_command"})
    void storageFailureAtAnyInsertRollsBackEntireDepartureAndTheOriginalKeyCanRetry(String table) throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main);Map<String,String> before=dispatchAndSourceDigest();String commandKey=key();
        failInsert(table);
        try { assertThat(company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),500,"E0500").isNull()).isTrue(); }
        finally { dropFailure(table); }
        noExecution();assertThat(dispatchAndSourceDigest()).isEqualTo(before);
        var retry=driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey);
        company(retry,200,"0000");assertThat(retry.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(count("tms_execution_event")).isEqualTo(1);assertThat(count("tms_execution_command")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"tms_execution_event","tms_execution_command"})
    void commitTimeDeferredForeignKeyUsesExecutionNamespaceAndRollsBackReceiptAndTransition(String table) throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main);Map<String,String> before=dispatchAndSourceDigest();String commandKey=key();
        jdbc.execute("CREATE FUNCTION logistics.test_execution_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN SET CONSTRAINTS ALL DEFERRED; NEW.actor_id='00000000-0000-4000-8000-000000009999'; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_execution_fail BEFORE INSERT ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_fail()");
        try { business(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),"E1706","PROTECTED_RELATION"); }
        finally { dropFailure(table); }
        noExecution();assertThat(dispatchAndSourceDigest()).isEqualTo(before);
        waiting(company(driverBrowser.get(ownPath(assigned)),200,"0000").get("execution"));
        company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),200,"0000");
        assertThat(count("tms_execution_event")).isEqualTo(1);assertThat(count("tms_execution_command")).isEqualTo(1);
    }

    @Test void receiptRemainsOriginalWhenLaterReservedReadPhaseHasAdvancedUnderTheSameAssignment() throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main);String commandKey=key();
        JsonNode original=company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey),200,"0000");
        // A future-version read fixture, not a progress API or approval of its later-package policy.
        jdbc.update("UPDATE logistics.tms_execution SET phase='IN_PROGRESS',version=2,updated_at=updated_at+INTERVAL '1 second' WHERE assignment_id=?",uuid(assigned));
        Map<String,String> before=allDigest();policy.mode=PolicyMode.UNCONFIRMED;
        var replay=driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),commandKey);
        assertThat(company(replay,200,"0000")).isEqualTo(original);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        JsonNode current=company(driverBrowser.get(ownPath(assigned)),200,"0000").get("execution");
        assertThat(current.get("phase").asString()).isEqualTo("IN_PROGRESS");assertThat(current.get("version").asLong()).isEqualTo(2);
        business(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",2),key()),"E1702","INVALID_STATE");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void committedExecutionSnapshotEventsAndCommandReceiptCannotBeRewrittenOrDeleted() throws Exception {
        JsonNode assigned=assigned("LOCAL",driver,main);
        company(driverBrowser.write("POST",departPath(assigned),fields("expectedVersion",0),key()),200,"0000");Map<String,String> before=allDigest();
        for(String statement:List.of("UPDATE logistics.tms_execution SET departed_at=departed_at+INTERVAL '1 second'",
                "DELETE FROM logistics.tms_execution", "UPDATE logistics.tms_execution_event SET occurred_at=occurred_at+INTERVAL '1 second'",
                "DELETE FROM logistics.tms_execution_event", "UPDATE logistics.tms_execution_command SET original_data='{}'::json",
                "DELETE FROM logistics.tms_execution_command"))
            assertThatThrownBy(()->jdbc.update(statement)).isInstanceOf(DataAccessException.class);
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void databaseRejectsInventedInitialProgressWrongTransitionAndCrossAssignmentReceiptIdentity() throws Exception {
        JsonNode first=assigned("LOCAL",driver,main),second=assigned("LOCAL",otherDriver,otherMain);
        company(driverBrowser.write("POST",departPath(first),fields("expectedVersion",0),key()),200,"0000");
        Map<String,String> before=allDigest();
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.tms_execution(assignment_id,phase,version,departed_at,updated_at) VALUES (?,'IN_PROGRESS',2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uuid(second))).isInstanceOf(DataAccessException.class);
        for(String transition:List.of("'DEPARTED','COMPLETED',1,2","'WAITING','DEPARTED',1,2"))
            assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.tms_execution_event(id,assignment_id,from_phase,to_phase,from_version,to_version,actor_id,occurred_at,snapshot) VALUES (?,?,"+transition+",?,CURRENT_TIMESTAMP,'{}'::json)",UUID.randomUUID(),uuid(first),driverAccount.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.tms_execution_command(id,company_scope,actor_id,driver_id,http_method,canonical_path,idempotency_key,request_hash,assignment_id,task_id,original_status,original_data,created_at) SELECT ?,company_scope,actor_id,driver_id,http_method,canonical_path,?,request_hash,assignment_id,?,original_status,original_data,created_at FROM logistics.tms_execution_command WHERE assignment_id=?",
                UUID.randomUUID(),key(),UUID.fromString(second.get("taskId").asString()),uuid(first))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.tms_execution_command(id,company_scope,actor_id,driver_id,http_method,canonical_path,idempotency_key,request_hash,assignment_id,task_id,original_status,original_data,created_at) SELECT ?,company_scope,actor_id,?,http_method,canonical_path,?,request_hash,assignment_id,task_id,original_status,original_data,created_at FROM logistics.tms_execution_command WHERE assignment_id=?",
                UUID.randomUUID(),uuid(otherDriver),key(),uuid(first))).isInstanceOf(DataAccessException.class);
        assertThat(allDigest()).isEqualTo(before);
    }

    private JsonNode assigned(String type,JsonNode selectedDriver,JsonNode selectedMain) throws Exception {
        JsonNode order=acceptedOrder(type);UUID task=null;
        for(JsonNode candidate:order.get("tasks"))if(candidate.get("predecessorRefs").isEmpty())task=uuid(candidate);
        assertThat(task).isNotNull();
        return company(admin.write("POST","/tms/tasks/"+task+"/dispatch",fields("expectedGeneration",0,"driverId",id(selectedDriver),"mainVehicleId",id(selectedMain),"trailerId",null),key()),200,"0000").get("assignment");
    }
    private JsonNode acceptedOrder(String type) throws Exception {
        JsonNode customer=company(admin.write("POST","/crm/customers",fields("customerCode","SYN-EXEC-"+key().toUpperCase(java.util.Locale.ROOT),"name","合成出發客戶"),null),201,"0000");
        ObjectNode draft=(ObjectNode)TomContract.example("graphCases","DEMO-"+type+"-CONTRACT").get("draft").deepCopy();draft.put("customerId",id(customer));
        draft.put("remark","不得暴露的合成整單備註");
        for(JsonNode raw:draft.get("tasks"))((ObjectNode)raw).put("taskKind","SYNTHETIC_TMS_IT_"+raw.get("clientRef").asString());
        ((ObjectNode)draft.get("items").get(0)).put("grossWeight","0.0000000000000000001");
        ObjectNode first=(ObjectNode)draft.get("tasks").get(0);first.put("pickupAddress"," 合成\u0000出發地址 e\u0301 ")
                .put("plannedStartAt","1500-01-02T00:00:00.123456+23:59").put("plannedEndAt","1500-01-02T01:00:00.123456+23:59");first.set("siteContact",json.createObjectNode());
        JsonNode created=tom(admin.write("POST","/orders",draft,key()),201);
        return tom(admin.write("POST","/orders/"+id(created)+"/accept",fields("expectedVersion",0),key()),200);
    }
    private UUID dictionaryType() throws Exception {
        UUID category=null;for(JsonNode candidate:company(admin.get("/adm/categories?size=100"),200,"0000").get("content"))if(candidate.get("code").asString().equals("VEHICLE_TYPE"))category=uuid(candidate);
        assertThat(category).isNotNull();return uuid(company(admin.write("POST","/adm/categories/"+category+"/items",fields("code","SYNTHETIC_TMS_IT_MAIN","label","合成出發車種","enabled",true,"sortOrder",1),null),201,"0000"));
    }
    private JsonNode driver(Account account,int number) throws Exception {return company(admin.write("POST","/fms/drivers",fields("driverCode","SYNTHETIC_TMS_IT_D"+number,"name","合成出發司機"+number,"dutyStatus","ON_DUTY","accountId",account.id().toString()),null),201,"0000");}
    private JsonNode vehicle(UUID type) throws Exception {return company(admin.write("POST","/fms/vehicles",fields("plateNo","SYN-"+key().substring(0,12).toUpperCase(java.util.Locale.ROOT),"vehicleType",type.toString(),"capacityTon","12.3400"),null),201,"0000");}
    private String managerPath(JsonNode assignment,String action){return "/tms/dispatches/"+id(assignment)+"/"+action;}
    private Map<String,Object> managerBody(String action){return action.equals("revoke")?fields("expectedGeneration",1,"reason","合成出發撤派競爭"):fields("expectedGeneration",1,"reason","合成出發改派競爭","driverId",id(otherDriver),"mainVehicleId",id(otherMain),"trailerId",null);}
    private String ownPath(JsonNode assigned){return "/tms/my-tasks/"+id(assigned);}
    private String departPath(JsonNode assigned){return ownPath(assigned)+"/depart";}
    private String id(JsonNode value){return value.get("id").asString();}
    private UUID uuid(JsonNode value){return UUID.fromString(id(value));}
    private String key(){return UUID.randomUUID().toString();}
    private static Map<String,Object> fields(Object... entries){var result=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;}
    private void waiting(JsonNode execution){shape(execution,List.of("phase","version","departedAt"));assertThat(execution.get("phase").asString()).isEqualTo("WAITING");assertThat(execution.get("version").asLong()).isZero();assertThat(execution.get("departedAt").isNull()).isTrue();}
    private void departureResult(JsonNode value,JsonNode assigned,Instant at){shape(value,List.of("assignmentId","taskId","execution"));assertThat(value.get("assignmentId").asString()).isEqualTo(id(assigned));assertThat(value.get("taskId")).isEqualTo(assigned.get("taskId"));shape(value.get("execution"),List.of("phase","version","departedAt"));assertThat(value.get("execution").get("phase").asString()).isEqualTo("DEPARTED");assertThat(value.get("execution").get("version").asLong()).isEqualTo(1);assertThat(Instant.parse(value.get("execution").get("departedAt").asString())).isEqualTo(at);}
    private void noExecution(){for(String table:EXECUTION_TABLES)assertThat(count(table)).as("No invented execution fact in %s",table).isZero();}
    private long count(String table){return jdbc.queryForObject("SELECT count(*) FROM logistics."+table,Long.class);}
    private Map<String,String> allDigest(){var result=dispatchAndSourceDigest();for(String table:EXECUTION_TABLES)result.put(table,digest(table,table.equals("tms_execution")?"assignment_id":"id"));return result;}
    private Map<String,String> dispatchAndSourceDigest(){var result=new LinkedHashMap<String,String>();for(String table:DISPATCH_TABLES)result.put(table,digest(table,table.equals("tms_task_dispatch")?"task_id":"id"));for(String table:List.of("order_core","tom_order","cargo_item","transport_task","task_dependency","order_draft_revision","api_idempotency","order_acceptance_event","crm_customer","crm_contact","crm_address"))result.put(table,digest(table,table.equals("tom_order")?"order_id":table.equals("task_dependency")?"task_id,predecessor_task_id":"id"));return result;}
    private String digest(String table,String ordering){return jdbc.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+ordering+")::text,'[]')) FROM logistics."+table+" t",String.class);}
    private void shape(JsonNode value,List<String> expected){assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);}
    private JsonNode company(HttpResponse<String> reply,int status,String code){JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).as("Execution HTTP status; code %s",body.get("code").asString()).isEqualTo(status);shape(body,List.of("code","message","data","timestamp"));assertThat(body.get("code").asString()).isEqualTo(code);assertThat(body.get("message").isString()).isTrue();assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));return body.get("data");}
    private JsonNode business(HttpResponse<String> reply,String code,String reason){JsonNode error=company(reply,409,code);assertThat(error.get("reason").asString()).isEqualTo(reason);return error;}
    private JsonNode tom(HttpResponse<String> reply,int status){JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).isEqualTo(status);TomContract.assertSchema("OrderEnvelope",body);return body.get("data");}
    private Account account(String role,boolean temporary)throws Exception{String username="exec-test-"+key().substring(0,12),password=UpmsTestData.password();try(var connection=dataSource.getConnection()){return new Account(UpmsTestData.insert(connection,encoder,username,role,password,temporary,clock.instant()),username,password);}}
    private Browser browser(){var browser=new Browser();browsers.add(browser);return browser;}
    private Browser login(Account account)throws Exception{var browser=browser();company(browser.write("POST","/auth/login",fields("username",account.username(),"password",account.password()),null),200,"0000");return browser;}
    private void canonical(Connection connection)throws Exception{try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();}}
    private void awaitLock(String fragment)throws Exception{awaitLockCount(fragment,1);}
    private void awaitLockCount(String fragment,int minimum)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);try(var observer=dataSource.getConnection();var statement=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")){observer.setAutoCommit(true);statement.setQueryTimeout(2);statement.setString(1,"%"+fragment+"%");while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getLong(1)>=minimum)return;}Thread.sleep(20);}}throw new AssertionError("Expected PostgreSQL execution lock wait for "+fragment);}
    private void pause(String table,String event){jdbc.execute("CREATE FUNCTION logistics.test_execution_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202641,1); RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_execution_pause "+event+" ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_pause()");}
    private void unpause(String table){jdbc.execute("DROP TRIGGER test_execution_pause ON logistics."+table);jdbc.execute("DROP FUNCTION logistics.test_execution_pause()");}
    private void advisory(Connection connection,boolean acquire)throws Exception{try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute("SELECT pg_advisory_"+(acquire?"lock":"unlock")+"(202641,1)");}}
    private void failInsert(String table){jdbc.execute("CREATE FUNCTION logistics.test_execution_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic execution store failure'; END $$");jdbc.execute("CREATE TRIGGER test_execution_fail BEFORE INSERT ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_fail()");}
    private void dropFailure(String table){jdbc.execute("DROP TRIGGER test_execution_fail ON logistics."+table);jdbc.execute("DROP FUNCTION logistics.test_execution_fail()");}
    private URI uri(String path){return URI.create("https://localhost:"+port+"/api/v1"+path);}
    private record Account(UUID id,String username,String password){}
    private final class Browser {
        private final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).sslContext(TlsTestSupport.trustedContext()).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,null,null);}
        String csrf()throws Exception{return company(get("/auth/csrf"),200,"0000").get("token").asString();}
        HttpResponse<String> write(String method,String path,Object payload,String key)throws Exception{return send(method,path,json.writeValueAsString(payload),key,csrf());}
        HttpResponse<String> send(String method,String path,String payload,String key,String token)throws Exception{var request=request(method,path,payload==null?null:payload.getBytes(StandardCharsets.UTF_8),payload==null?null:"application/json",token);if(key!=null)request.header("Idempotency-Key",key);return client.send(request.build(),HttpResponse.BodyHandlers.ofString());}
        HttpRequest.Builder request(String method,String path,byte[] payload,String contentType,String token){var result=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Accept","application/json");if(contentType!=null)result.header("Content-Type",contentType);if(token!=null)result.header("X-CSRF-TOKEN",token);return result.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(payload));}
    }
}
