package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.tms.confirmation.ManagementCompletionPolicy;
import com.xksystem.logistics.tom.closure.OrderClosurePolicy;
import com.xksystem.logistics.support.SyntheticManagementPolicies;
import com.xksystem.logistics.tms.completion.CompletionPolicy;
import com.xksystem.logistics.support.SyntheticCompletionPolicy;
import com.xksystem.logistics.tms.exceptions.ExceptionPolicy;
import com.xksystem.logistics.tms.exceptions.ExceptionQueries;
import com.xksystem.logistics.support.SyntheticExceptionPolicy;
import com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy;
import com.xksystem.logistics.tms.evidence.EvidenceStorage;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import com.xksystem.logistics.support.SyntheticEvidencePolicy;
import com.xksystem.logistics.support.SyntheticEvidenceStorage;
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

/** Private verified evidence and progress exercised through real TLS/security and PostgreSQL transactions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ManagementClosureIT.Configuration.class)
class ManagementClosureIT {
    private static final List<String> EXCEPTION_TABLES=List.of("tms_exception","tms_exception_event","tms_exception_command","tms_exception_supplement_request","tms_exception_supplement_evidence","tms_exception_event_evidence");
    private static final List<String> RECORD_FIELDS=List.of("id","assignmentId","taskId","taskNo","reportedPhase","reportedExecutionVersion","description","status","version","blocking","reportedAt","resolvedAt","resolutionNote","activeRequest");
    private static final List<String> SUMMARY_FIELDS=List.of("id","assignmentId","taskId","taskNo","reportedPhase","status","version","blocking","reportedAt");
    private static final List<String> EVENT_FIELDS=List.of("id","exceptionId","type","fromStatus","toStatus","fromVersion","toVersion","occurredAt","note","requestId","evidenceIds");
    private static final List<String> MY_FIELDS = List.of("assignmentId", "taskId", "taskNo", "item", "taskKind",
            "pickupName", "pickupAddress", "deliveryName", "deliveryAddress", "siteContact", "plannedStartAt",
            "plannedEndAt", "assignedAt", "policyMode");
    private static final List<String> EVIDENCE_TABLES=List.of("tms_execution_evidence","tms_execution_evidence_object","tms_execution_event_evidence","tms_execution_evidence_command");
    private static final List<String> EVIDENCE_FIELDS=List.of("id","assignmentId","taskId","phase","executionVersion","mediaType","byteSize","sha256","uploadedAt","status");
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
        @Bean @Primary ToggleManagementPolicy syntheticToggleManagementPolicy(DataSource source)throws Exception{return new ToggleManagementPolicy(SyntheticManagementPolicies.confirmation(source));}
        @Bean @Primary ToggleClosurePolicy syntheticToggleClosurePolicy(DataSource source)throws Exception{return new ToggleClosurePolicy(SyntheticManagementPolicies.closure(source));}
        @Bean @Primary ToggleCompletionPolicy syntheticToggleCompletionPolicy(DataSource dataSource)throws Exception{return new ToggleCompletionPolicy(SyntheticCompletionPolicy.create(dataSource));}
        @Bean @Primary ToggleExceptionPolicy syntheticToggleExceptionPolicy(DataSource dataSource)throws Exception{return new ToggleExceptionPolicy(SyntheticExceptionPolicy.create(dataSource));}
        @Bean @Primary ToggleEvidencePolicy evidencePolicy(DataSource dataSource) throws Exception { return new ToggleEvidencePolicy(SyntheticEvidencePolicy.create(dataSource)); }
        @Bean @Primary ToggleStorage privateStorage(DataSource dataSource) throws Exception { return new ToggleStorage(SyntheticEvidenceStorage.create(dataSource)); }
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
    static final class ToggleEvidencePolicy implements ExecutionEvidencePolicy {
        private final ExecutionEvidencePolicy delegate;
        volatile PolicyMode mode=PolicyMode.SYNTHETIC_TEST_ONLY;
        ToggleEvidencePolicy(ExecutionEvidencePolicy delegate){this.delegate=delegate;}
        @Override public PolicyMode mode(){return mode;}
        @Override public java.util.Optional<Requirements> requirements(TaskQueries.Source source,DriverQueries.Snapshot driver,ExecutionPhase phase){return delegate.requirements(source,driver,phase);}
        @Override public boolean canRead(TaskQueries.Source source,DriverQueries.Snapshot driver){return delegate.canRead(source,driver);}
    }
    static final class ToggleStorage implements EvidenceStorage {
        private final EvidenceStorage delegate;
        volatile boolean available=true,failStore,failLoad,corruptLoad;
        final java.util.concurrent.atomic.AtomicInteger loads=new java.util.concurrent.atomic.AtomicInteger();
        ToggleStorage(EvidenceStorage delegate){this.delegate=delegate;}
        @Override public PolicyMode mode(){return available?delegate.mode():PolicyMode.UNCONFIRMED;}
        @Override public void store(UUID id,byte[] bytes,String hash){if(failStore)throw new IllegalStateException("Synthetic private storage failure");delegate.store(id,bytes,hash);}
        @Override public byte[] load(UUID id){loads.incrementAndGet();if(failLoad)throw new IllegalStateException("Synthetic private read failure");byte[] value=delegate.load(id);if(corruptLoad&&value!=null){value=value.clone();value[value.length-1]^=1;}return value;}
        void reset(){available=true;failStore=false;failLoad=false;corruptLoad=false;loads.set(0);}
    }
    static final class ToggleExceptionPolicy implements ExceptionPolicy {
        final ExceptionPolicy delegate;volatile PolicyMode mode=PolicyMode.SYNTHETIC_TEST_ONLY;volatile String ruleFault;
        ToggleExceptionPolicy(ExceptionPolicy delegate){this.delegate=delegate;}
        @Override public PolicyMode mode(){return mode;}
        @Override public boolean allows(Action action,Context context){return delegate.allows(action,context);}
        @Override public java.util.Optional<ExecutionEvidencePolicy.Requirements> requirements(Context context){if("EMPTY".equals(ruleFault))return java.util.Optional.empty();if("INVALID".equals(ruleFault))return java.util.Optional.of(new ExecutionEvidencePolicy.Requirements(0,List.of("image/png"),65536,4096));return delegate.requirements(context);}
        @Override public boolean managerCanRead(TaskQueries.Source source,TmsDtos.DriverSnapshot driver){return delegate.managerCanRead(source,driver);}
    }
    static final class ToggleCompletionPolicy implements CompletionPolicy {
        final CompletionPolicy delegate;volatile PolicyMode mode=PolicyMode.SYNTHETIC_TEST_ONLY;
        ToggleCompletionPolicy(CompletionPolicy delegate){this.delegate=delegate;}
        @Override public PolicyMode mode(){return mode;}
        @Override public java.util.Optional<Requirements> requirements(TaskQueries.Source source,DriverQueries.Snapshot driver){return delegate.requirements(source,driver);}
    }
    static final class ToggleManagementPolicy implements ManagementCompletionPolicy {final ManagementCompletionPolicy delegate;volatile PolicyMode mode=PolicyMode.SYNTHETIC_TEST_ONLY;ToggleManagementPolicy(ManagementCompletionPolicy delegate){this.delegate=delegate;}public PolicyMode mode(){return mode;}public boolean allows(TaskQueries.Source source,TmsDtos.DriverSnapshot driver){return delegate.allows(source,driver);}}
    static final class ToggleClosurePolicy implements OrderClosurePolicy {final OrderClosurePolicy delegate;volatile PolicyMode mode=PolicyMode.SYNTHETIC_TEST_ONLY;ToggleClosurePolicy(OrderClosurePolicy delegate){this.delegate=delegate;}public PolicyMode mode(){return mode;}public boolean allows(List<TaskContext> tasks){return delegate.allows(tasks);}}
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    @Autowired TogglePolicy policy;
    @Autowired ToggleEvidencePolicy evidencePolicy;
    @Autowired ToggleStorage storage;
    @Autowired ToggleExceptionPolicy exceptionPolicy;
    @Autowired ToggleCompletionPolicy completionPolicy;
    @Autowired ToggleManagementPolicy managementPolicy;
    @Autowired ToggleClosurePolicy closurePolicy;
    @Autowired ExceptionQueries blockers;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired TaskQueries taskSources;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String,OrderSource> orderSources=new LinkedHashMap<>();
    private final List<Browser> browsers = new ArrayList<>();
    private Account administrator, driverAccount, otherAccount;
    private Browser admin, driverBrowser, otherBrowser;
    private JsonNode driver, otherDriver, main, otherMain;

    @BeforeEach void isolatedRealSourcesAndNormalCurrentDriverBindings() throws Exception {
        jdbc.execute("TRUNCATE TABLE logistics.tom_order_closure_command,logistics.tom_order_closure_task,logistics.tom_order_closure,logistics.tms_completion_confirmation_command,logistics.tms_completion_confirmation,logistics.tms_completion_command,logistics.tms_completion,logistics.tms_exception_event_evidence,logistics.tms_exception_supplement_evidence,logistics.tms_exception_supplement_request,logistics.tms_exception_command,logistics.tms_exception_event,logistics.tms_exception,logistics.tms_execution_evidence_command,logistics.tms_execution_event_evidence,logistics.tms_execution_evidence_object,logistics.tms_execution_evidence,logistics.tms_execution_command,logistics.tms_execution_event,logistics.tms_execution,"
                + "logistics.tms_idempotency,logistics.tms_dispatch_event,logistics.tms_resource_reservation,logistics.tms_assignment,logistics.tms_task_dispatch,"
                + "logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle,logistics.order_core,logistics.tom_order,"
                + "logistics.cargo_item,logistics.transport_task,logistics.task_dependency,logistics.order_draft_revision,logistics.api_idempotency,"
                + "logistics.crm_contact,logistics.crm_address,logistics.crm_customer,logistics.adm_change_log,logistics.upms_audit,logistics.upms_account");
        jdbc.update("DELETE FROM logistics.adm_dict_item WHERE NOT read_only");
        jdbc.update("UPDATE logistics.adm_dict_category SET enabled=true WHERE NOT read_only");
        clock.reset(); policy.mode = PolicyMode.SYNTHETIC_TEST_ONLY; evidencePolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;storage.reset();exceptionPolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;exceptionPolicy.ruleFault=null;completionPolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;managementPolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;closurePolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;orderSources.clear();
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





    @Test void administrativeCountersAreIndependentImmutableAcknowledgementsAndPendingReadsDoNotWriteFacts()throws Exception{
        Cohort flow=cohort(false);Map<String,String> before=allDigest();JsonNode detail=company(admin.get(confirmationPath(flow.firstCompletion())+"/confirmation"),200,"0000");shape(detail,List.of("record","execution","completion","confirmation","policy"));shape(detail.get("record"),CONFIRMATION_SUMMARY_FIELDS);assertThat(detail.get("record").get("version").asLong()).isZero();assertThat(detail.get("record").get("state").asString()).isEqualTo("PENDING");assertThat(detail.get("confirmation").isNull()).isTrue();assertThat(detail.get("execution").get("version").asLong()).isEqualTo(4);assertThat(detail.get("record").get("sourceOrderVersion").asLong()).isEqualTo(2);assertThat(company(admin.get(confirmationPath(flow.firstCompletion())+"/confirmation/history"),200,"0000").get("content")).isEmpty();assertThat(allDigest()).isEqualTo(before);
        JsonNode wrong=business(admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",4,"confirmCompletion",true,"reason",MANAGEMENT_REASON),key()),"E2101","VERSION_CONFLICT");shape(wrong,List.of("reason","actualConfirmationVersion"));assertThat(wrong.get("actualConfirmationVersion").asLong()).isZero();
        String confirmKey=key();JsonNode confirmed=confirm(flow.firstCompletion(),0,MANAGEMENT_REASON,confirmKey);confirmationResult(confirmed,flow.firstCompletion(),flow.source());
        var after=allDigest();for(String table:List.of("tms_completion_confirmation","tms_completion_confirmation_command"))after.remove(table);for(String table:List.of("tms_completion_confirmation","tms_completion_confirmation_command"))before.remove(table);assertThat(after).isEqualTo(before);
        JsonNode history=company(admin.get(confirmationPath(flow.firstCompletion())+"/confirmation/history"),200,"0000").get("content");assertThat(history).hasSize(1);assertThat(history.get(0)).isEqualTo(confirmed.get("confirmation"));
        managementPolicy.mode=PolicyMode.UNCONFIRMED;var replay=admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("reason",MANAGEMENT_REASON,"confirmCompletion",true,"expectedVersion",new java.math.BigDecimal("0.00")),confirmKey);assertThat(company(replay,200,"0000")).isEqualTo(confirmed);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        JsonNode already=company(admin.get(confirmationPath(flow.firstCompletion())+"/confirmation"),200,"0000");assertThat(already.get("policy").get("mode").asString()).isEqualTo("UNCONFIRMED");assertThat(already.get("policy").get("blocker").asString()).isEqualTo("ALREADY_CONFIRMED");
    }

    @Test void wholeTrueAndUnspecifiedTaskCohortClosesActualTomOnceWithExactTypedManifestAndOriginalRawRevision()throws Exception{
        Cohort flow=cohort(true);JsonNode review=company(admin.get(closureBase(flow.source())+"/closure/tasks?size=100"),200,"0000");assertThat(review.get("content")).hasSize(2);boolean sawNull=false;
        for(JsonNode task:review.get("content")){shape(task,REVIEW_FIELDS);assertThat(task.get("orderId").asString()).isEqualTo(id(flow.source().accepted()));assertThat(task.get("sourceOrderVersion").asLong()).isEqualTo(2);assertThat(task.get("completionVerified").asBoolean()).isTrue();assertThat(task.get("confirmationVersion").asLong()).isEqualTo(1);if(task.get("requiredTask").isNull())sawNull=true;else assertThat(task.get("requiredTask").asBoolean()).isTrue();}assertThat(sawNull).isTrue();
        company(admin.write("PATCH","/crm/customers/"+flow.source().accepted().get("customerId").asString(),fields("version",0,"enabled",false,"name","後來停用改名的合成客戶"),null),200,"0000");
        Map<String,String> before=priorFacts();managementPolicy.mode=PolicyMode.UNCONFIRMED;String closeKey=key();var reply=close(flow.source(),2,CLOSURE_REASON,closeKey);JsonNode result=company(reply,200,"0000");closureResult(result,flow.source());assertThat(reply.headers().firstValue("Idempotency-Replayed")).contains("false");assertThat(priorFacts()).isEqualTo(before);
        JsonNode oldGet=tom(admin.get("/orders/"+id(flow.source().accepted())),200);assertThat(oldGet.get("lifecycleStatus").asString()).isEqualTo("CLOSED");assertThat(oldGet.get("version").asLong()).isEqualTo(3);assertThat(oldGet.get("customerName")).isEqualTo(flow.source().accepted().get("customerName"));assertThat(oldGet.get("items")).isEqualTo(flow.source().accepted().get("items"));assertThat(oldGet.get("tasks")).isEqualTo(flow.source().accepted().get("tasks"));assertThat(oldGet.get("remark")).isEqualTo(flow.source().accepted().get("remark"));
        JsonNode revision=json.readTree(jdbc.queryForObject("SELECT snapshot::text FROM logistics.order_draft_revision WHERE order_id=? AND aggregate_version=3",String.class,uuid(flow.source().accepted())));assertThat(revision).isEqualTo(oldGet);
        JsonNode recorded=company(admin.get(closureBase(flow.source())+"/closure"),200,"0000");assertThat(recorded.get("auditState").asString()).isEqualTo("RECORDED");assertThat(recorded.get("counts").isNull()).isTrue();assertThat(recorded.get("closure")).isEqualTo(result.get("closure"));assertThat(company(admin.get(closureBase(flow.source())+"/closure/tasks?size=100"),200,"0000").get("content")).isEqualTo(review.get("content"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.tom_order_closure_task WHERE order_id=?",Long.class,uuid(flow.source().accepted()))).isEqualTo(2);
        assertThat(company(admin.get(closureBase(flow.source())+"/closure/history"),200,"0000").get("content").get(0)).isEqualTo(result.get("closure"));
        closurePolicy.mode=PolicyMode.UNCONFIRMED;var replay=close(flow.source(),2,CLOSURE_REASON,closeKey);assertThat(company(replay,200,"0000")).isEqualTo(result);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");JsonNode closedPolicy=company(admin.get(closureBase(flow.source())+"/closure"),200,"0000").get("policy");assertThat(closedPolicy.get("mode").asString()).isEqualTo("UNCONFIRMED");assertThat(closedPolicy.get("blocker").asString()).isEqualTo("ALREADY_CLOSED");assertThat(closedPolicy.get("requirements").isNull()).isTrue();
    }

    @Test void closedOrderKeepsOriginalTomAndManagementHistoricalReceiptsAndNarrowCompletedDriverRead()throws Exception{
        Cohort flow=cohort(true);company(close(flow.source(),2,CLOSURE_REASON,key()),200,"0000");
        assertThat(tom(admin.write("POST","/orders",flow.source().draft(),flow.source().createKey()),201)).isEqualTo(flow.source().created());assertThat(tom(admin.write("PUT","/orders/"+id(flow.source().accepted()),flow.source().putBody(),flow.source().putKey()),200)).isEqualTo(flow.source().replaced());JsonNode acceptReplay=tom(admin.write("POST","/orders/"+id(flow.source().accepted())+"/accept",fields("expectedVersion",1),flow.source().acceptKey()),200);assertThat(acceptReplay).isEqualTo(flow.source().accepted());assertThat(acceptReplay.get("lifecycleStatus").asString()).isEqualTo("OPEN");
        managementPolicy.mode=PolicyMode.UNCONFIRMED;var confirmReplay=admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON),flow.confirmationKeys().get(0));assertThat(company(confirmReplay,200,"0000")).isEqualTo(flow.confirmations().get(0));assertThat(confirmReplay.headers().firstValue("Idempotency-Replayed")).contains("true");
        company(driverBrowser.get(completionBase(flow.first())+"/completion"),200,"0000");company(driverBrowser.get(completionBase(flow.first())),404,"E0404");
        var stale=admin.write("PUT","/orders/"+id(flow.source().accepted()),fields("expectedVersion",2,"draft",flow.source().draft()),key());assertThat(stale.statusCode()).isEqualTo(409);TomContract.assertSchema("ErrorEnvelope",json.readTree(stale.body()));assertThat(json.readTree(stale.body()).get("code").asString()).isEqualTo("VERSION_CONFLICT");
        var noneditable=admin.write("PUT","/orders/"+id(flow.source().accepted()),fields("expectedVersion",3,"draft",flow.source().draft()),key());assertThat(noneditable.statusCode()).isEqualTo(409);TomContract.assertSchema("ErrorEnvelope",json.readTree(noneditable.body()));assertThat(json.readTree(noneditable.body()).get("code").asString()).isEqualTo("ORDER_NOT_EDITABLE");
    }

    @Test void missingUnspecifiedTaskOrAdministrativeAcknowledgementCannotBeWaivedByCountsOrCheckbox()throws Exception{
        OrderSource source=orderSource("LOCAL");UUID first=null;for(JsonNode task:source.accepted().get("tasks"))if(task.get("predecessorRefs").isEmpty())first=uuid(task);Proof proof=taskProof(source,first);JsonNode complete=company(complete(proof,3,proof.ids(),key()),200,"0000");confirm(complete,0,MANAGEMENT_REASON,key());
        JsonNode view=company(admin.get(closureBase(source)+"/closure"),200,"0000");assertThat(view.get("counts").get("taskCount").asLong()).isEqualTo(2);assertThat(view.get("counts").get("completedTaskCount").asLong()).isEqualTo(1);Map<String,String> before=allDigest();business(close(source,2,CLOSURE_REASON,key()),"E2107","TASKS_INCOMPLETE");assertThat(allDigest()).isEqualTo(before);
    }

    @Test void newlySatisfiedConfirmationCanMakeSameSourceVersionEligibleWithoutClientProofDigest()throws Exception{
        Cohort flow=cohort(false);confirm(flow.firstCompletion(),0,MANAGEMENT_REASON,key());Map<String,String> before=allDigest();business(close(flow.source(),2,CLOSURE_REASON,key()),"E2108","CONFIRMATION_REQUIRED");assertThat(allDigest()).isEqualTo(before);confirm(flow.secondCompletion(),0,MANAGEMENT_REASON,key());
        JsonNode view=company(admin.get(closureBase(flow.source())+"/closure"),200,"0000");assertThat(view.get("policy").get("canClose").asBoolean()).isTrue();assertThat(view.get("counts").get("sourceOrderVersion").asLong()).isEqualTo(2);company(close(flow.source(),2,CLOSURE_REASON,key()),200,"0000");
    }

    @Test void legacyClosedOrdersRemainRealAuditAbsentAndDoNotAcquireInventedHistoryOrTaskReview()throws Exception{
        OrderSource source=orderSource("LOCAL");jdbc.update("UPDATE logistics.order_core SET lifecycle_status='CLOSED' WHERE id=?",uuid(source.accepted()));Map<String,String> before=allDigest();
        JsonNode view=company(admin.get(closureBase(source)+"/closure"),200,"0000");assertThat(view.get("auditState").asString()).isEqualTo("LEGACY_CLOSED");assertThat(view.get("closure").isNull()).isTrue();assertThat(view.get("counts").isNull()).isTrue();assertThat(company(admin.get(closureBase(source)+"/closure/history"),200,"0000").get("content")).isEmpty();business(admin.get(closureBase(source)+"/closure/tasks"),"E2102","INVALID_STATE");
        JsonNode old=tom(admin.get("/orders/"+id(source.accepted())),200);assertThat(old.get("lifecycleStatus").asString()).isEqualTo("CLOSED");business(close(source,2,CLOSURE_REASON,key()),"E2102","INVALID_STATE");
        jdbc.update("UPDATE logistics.tom_order SET customer_name='合法舊結案無新witness的測試校正' WHERE order_id=?",uuid(source.accepted()));assertThat(count("tom_order_closure")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"MANAGEMENT","CLOSURE"})
    void independentCurrentPoliciesCannotBeApprovedByGenuineSyntheticHistoricalFacts(String closedPolicy)throws Exception{
        Cohort flow=cohort(closedPolicy.equals("CLOSURE"));Map<String,String> before=allDigest();
        if(closedPolicy.equals("MANAGEMENT")){managementPolicy.mode=PolicyMode.UNCONFIRMED;business(admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON),key()),"E2103","POLICY_NOT_CONFIRMED");}
        else{closurePolicy.mode=PolicyMode.UNCONFIRMED;business(close(flow.source(),2,CLOSURE_REASON,key()),"E2103","POLICY_NOT_CONFIRMED");}
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void nineRoutesHaveFreshManagerCompanyScopeStrictBoundedQueriesAndSeparateTomEnvelopes()throws Exception{
        Cohort flow=cohort(false);Browser dispatcher=login(account("DISPATCHER",false)),anonymous=browser();List<String> routes=List.of("/tms/completions",confirmationPath(flow.firstCompletion())+"/confirmation",confirmationPath(flow.firstCompletion())+"/confirmation/history","/orders/closure-worklist",closureBase(flow.source())+"/closure",closureBase(flow.source())+"/closure/tasks",closureBase(flow.source())+"/closure/history");
        for(String route:routes){company(dispatcher.get(route),200,"0000");company(driverBrowser.get(route),403,"E0403");company(anonymous.get(route),401,"E0401");}
        company(driverBrowser.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON),key()),403,"E0403");company(driverBrowser.write("POST",closureBase(flow.source())+"/close",fields("expectedVersion",2,"confirmClosure",true,"reason",CLOSURE_REASON),key()),403,"E0403");
        for(String query:List.of("page=-1","page=100001","size=101","state=OTHER","page=0&page=1","customerId="+id(flow.source().accepted()),"sort=id")){company(admin.get("/tms/completions?"+query),400,"E0400");company(admin.get("/orders/closure-worklist?"+query),400,"E0400");}
        for(String route:List.of(confirmationPath(flow.firstCompletion())+"/confirmation",closureBase(flow.source())+"/closure"))company(admin.get(route+"?accountId="+administrator.id()),400,"E0400");
        JsonNode pending=company(admin.get("/tms/completions?state=PENDING&size=1"),200,"0000");assertThat(pending.get("totalElements").asLong()).isEqualTo(2);assertThat(pending.get("content")).hasSize(1);shape(pending.get("content").get(0),CONFIRMATION_SUMMARY_FIELDS);
        assertThat(company(admin.get("/orders/closure-worklist?page=100000&size=1"),200,"0000").get("content")).isEmpty();
    }


    @ParameterizedTest @ValueSource(strings={"tms_completion_confirmation","tms_completion_confirmation_command"})
    void administrativeFactAndReceiptFailureNeverMutatesCompletionOrAcknowledgementCounter(String table)throws Exception{
        Cohort flow=cohort(false);Map<String,String> before=allDigest();String commandKey=key();failAt(table,"BEFORE INSERT");
        try{company(admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON),commandKey),500,"E0500");}finally{dropFailure(table);}assertThat(allDigest()).isEqualTo(before);confirm(flow.firstCompletion(),0,MANAGEMENT_REASON,commandKey);
    }

    @ParameterizedTest @ValueSource(strings={"order_core","tom_order","order_draft_revision","tom_order_closure","tom_order_closure_task","tom_order_closure_command"})
    void eachRootRevisionManifestWitnessOrReceiptFailureRollsBackActualClosureAsOneTransaction(String table)throws Exception{
        Cohort flow=cohort(true);Map<String,String> before=allDigest();String commandKey=key();failAt(table,table.equals("order_core")||table.equals("tom_order")?"BEFORE UPDATE":"BEFORE INSERT");
        try{company(close(flow.source(),2,CLOSURE_REASON,commandKey),500,"E0500");}finally{dropFailure(table);}assertThat(allDigest()).isEqualTo(before);assertThat(tom(admin.get("/orders/"+id(flow.source().accepted())),200).get("lifecycleStatus").asString()).isEqualTo("OPEN");company(close(flow.source(),2,CLOSURE_REASON,commandKey),200,"0000");
    }

    @ParameterizedTest @ValueSource(strings={"tms_completion_confirmation","tms_completion_confirmation_command","tom_order_closure","tom_order_closure_command"})
    void deferredActorRelationsUseE21AndCannotLeaveLooseManagementOrClosureWitness(String table)throws Exception{
        boolean closing=table.startsWith("tom_");Cohort flow=cohort(closing);Map<String,String> before=allDigest();
        jdbc.execute("CREATE FUNCTION logistics.test_execution_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN SET CONSTRAINTS ALL DEFERRED; NEW.actor_id='00000000-0000-4000-8000-000000009999'; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_execution_fail BEFORE INSERT ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_fail()");
        try{HttpResponse<String> reply=closing?close(flow.source(),2,CLOSURE_REASON,key()):admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON),key());business(reply,"E2106","PROTECTED_RELATION");}finally{dropFailure(table);}assertThat(allDigest()).isEqualTo(before);
    }

    @Test void sourceRawEqualityUsesAcceptedSnapshotNotOnlyIdsAndUnchangedVersion()throws Exception{
        Cohort flow=cohort(false);jdbc.update("UPDATE logistics.tom_order SET customer_name='被未升版SQL改動的來源',remark=? WHERE order_id=?"," raw\u0000篡改 e\u0301 ".getBytes(StandardCharsets.UTF_8),uuid(flow.source().accepted()));Map<String,String> before=allDigest();
        JsonNode detail=company(admin.get(confirmationPath(flow.firstCompletion())+"/confirmation"),200,"0000");assertThat(detail.get("policy").get("canConfirm").asBoolean()).isFalse();assertThat(detail.get("policy").get("blocker").asString()).isEqualTo("PROOF_INVALID");business(admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON),key()),"E2102","INVALID_STATE");
        JsonNode view=company(admin.get(closureBase(flow.source())+"/closure"),200,"0000");assertThat(view.get("policy").get("canClose").asBoolean()).isFalse();assertThat(view.get("policy").get("blocker").asString()).isEqualTo("PROOF_INVALID");business(close(flow.source(),2,CLOSURE_REASON,key()),"E2102","INVALID_STATE");assertThat(allDigest()).isEqualTo(before);
    }

    @Test void strictDualCountersRawReasonsAndStreamCapsNeverMasqueradeAsOldTomErrors()throws Exception{
        Cohort flow=cohort(true);Map<String,String> before=allDigest();JsonNode stale=business(close(flow.source(),4,CLOSURE_REASON,key()),"E2101","VERSION_CONFLICT");shape(stale,List.of("reason","actualOrderVersion"));assertThat(stale.get("actualOrderVersion").asLong()).isEqualTo(2);
        for(Object confirmation:List.of(false,"true",1))company(admin.write("POST",closureBase(flow.source())+"/close",fields("expectedVersion",2,"confirmClosure",confirmation,"reason",CLOSURE_REASON),key()),400,"E0400");
        for(String reason:List.of(" ","\u00a0\u202f\ufeff"))company(admin.write("POST",closureBase(flow.source())+"/close",fields("expectedVersion",2,"confirmClosure",true,"reason",reason),key()),400,"E0400");
        for(String raw:List.of("{}","null","[]","{\"expectedVersion\":2,\"expectedVersion\":2,\"confirmClosure\":true,\"reason\":\"x\"}","{\"expectedVersion\":2,\"confirmClosure\":true,\"reason\":\"x\",\"waiveTask\":true}","{\"expectedVersion\":2.1,\"confirmClosure\":true,\"reason\":\"x\"}","{\"expectedVersion\":2,\"confirmClosure\":true,\"reason\":\"\\ud800\"}"))company(admin.send("POST",closureBase(flow.source())+"/close",raw,key(),admin.csrf()),400,"E0400");
        String valid=json.writeValueAsString(fields("expectedVersion",2,"confirmClosure",true,"reason",CLOSURE_REASON)),oversized=" ".repeat(17000)+valid;byte[] bytes=oversized.getBytes(StandardCharsets.UTF_8);
        for(boolean chunked:List.of(false,true)){var request=admin.request("POST",closureBase(flow.source())+"/close",bytes,"application/json",admin.csrf()).header("Idempotency-Key",key());if(chunked)request.POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(bytes)));company(admin.client.send(request.build(),HttpResponse.BodyHandlers.ofString()),413,"E0413");}
        company(admin.send("POST",closureBase(flow.source())+"/close",valid,key(),null),403,"E0403");company(admin.send("POST",closureBase(flow.source())+"/close?force=true",valid,key(),admin.csrf()),400,"E0400");assertThat(allDigest()).isEqualTo(before);
    }

    @Test void ownedClosedRootGraphAnchorsAndAdministrativeManifestCannotDriftAfterRecordedSuccess()throws Exception{
        Cohort flow=cohort(true);company(close(flow.source(),2,CLOSURE_REASON,key()),200,"0000");String order=id(flow.source().accepted()),actor=administrator.id().toString();Map<String,String> before=allDigest();
        List<String> mutations=List.of("UPDATE logistics.order_core SET order_no=order_no||'-late' WHERE id='"+order+"'","UPDATE logistics.order_core SET updated_at=updated_at+INTERVAL '1 second' WHERE id='"+order+"'","UPDATE logistics.tom_order SET customer_name='late',remark=decode('7800','hex'),order_type='IMPORT',updated_at=updated_at+INTERVAL '1 second' WHERE order_id='"+order+"'","UPDATE logistics.cargo_item SET package_qty=coalesce(package_qty,1)+1 WHERE order_id='"+order+"'","UPDATE logistics.transport_task SET pickup_address=decode('7800','hex') WHERE order_id='"+order+"'","UPDATE logistics.task_dependency SET input_position=input_position+1 WHERE order_id='"+order+"'","UPDATE logistics.tms_task_dispatch SET task_no=task_no||'-late' WHERE order_id='"+order+"'","UPDATE logistics.tms_assignment SET source_snapshot=source_snapshot WHERE order_id='"+order+"'","INSERT INTO logistics.cargo_item(id,order_id,client_ref,input_position,sequence,item_kind,created_at,updated_at,created_by,updated_by) VALUES ('"+key()+"','"+order+"','late-item',99,99,'CARGO',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'"+actor+"','"+actor+"')");
        for(String sql:mutations)assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(DataAccessException.class);
        for(String table:List.of("tms_completion_confirmation","tms_completion_confirmation_command","tom_order_closure","tom_order_closure_task","tom_order_closure_command")){String column=table.equals("tom_order_closure_task")?"task_id":"id";assertThatThrownBy(()->jdbc.update("UPDATE logistics."+table+" SET "+column+"="+column)).isInstanceOf(DataAccessException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM logistics."+table)).isInstanceOf(DataAccessException.class);}
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void ownedRootSealUsesNowaitAfterImplicitChildTupleLocksIncludingBothDependencyEndpointsAndTmsIdentity()throws Exception{
        Cohort flow=cohort(true);company(close(flow.source(),2,CLOSURE_REASON,key()),200,"0000");OrderSource unowned=orderSource("LOCAL");UUID ownedOrder=uuid(flow.source().accepted()),foreignOrder=uuid(unowned.accepted());String ownedFirst=flow.first().ready().assignment().get("taskId").asString();
        List<String> sqls=List.of("UPDATE logistics.task_dependency SET predecessor_task_id='"+ownedFirst+"' WHERE order_id='"+foreignOrder+"'","UPDATE logistics.task_dependency SET input_position=input_position WHERE order_id='"+ownedOrder+"'","UPDATE logistics.tms_assignment SET source_snapshot=source_snapshot WHERE id='"+id(flow.first().ready().assignment())+"'","UPDATE logistics.tms_task_dispatch SET generation=generation WHERE order_id='"+ownedOrder+"'","UPDATE logistics.order_core SET updated_at=updated_at WHERE id='"+ownedOrder+"'");
        Map<String,String> before=allDigest();try(var held=dataSource.getConnection()){held.setAutoCommit(false);try(var lock=held.prepareStatement("SELECT order_id FROM logistics.tom_order WHERE order_id=? FOR UPDATE")){lock.setObject(1,ownedOrder);lock.executeQuery().close();}
            for(String sql:sqls)try(var child=dataSource.getConnection();var statement=child.createStatement()){statement.setQueryTimeout(3);long start=System.nanoTime();assertThatThrownBy(()->statement.executeUpdate(sql)).isInstanceOfSatisfying(java.sql.SQLException.class,error->assertThat(error.getSQLState()).isEqualTo("55P03"));assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));}held.rollback();}
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void staleRepeatableReadSnapshotCannotModifyChildAfterOwnedClosureChangesItsRootVersion()throws Exception{
        Cohort flow=cohort(true);try(var stale=dataSource.getConnection()){stale.setAutoCommit(false);stale.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);try(var read=stale.prepareStatement("SELECT version FROM logistics.tom_order WHERE order_id=?")){read.setObject(1,uuid(flow.source().accepted()));try(var row=read.executeQuery()){row.next();assertThat(row.getLong(1)).isEqualTo(2);}}
            company(close(flow.source(),2,CLOSURE_REASON,key()),200,"0000");try(var write=stale.prepareStatement("UPDATE logistics.cargo_item SET package_qty=coalesce(package_qty,1)+1 WHERE order_id=?")){write.setQueryTimeout(5);write.setObject(1,uuid(flow.source().accepted()));assertThatThrownBy(write::executeUpdate).isInstanceOfSatisfying(java.sql.SQLException.class,error->assertThat(error.getSQLState()).isEqualTo("40001"));}finally{stale.rollback();}}
    }

    @Test void revisionAsciiMetadataLensPreservesRawJsonAndCannotTurnNulPoisonedKeysOrValuesIntoValidHeaders()throws Exception{
        Map<String,String> expected=Map.of("id","00000000-0000-4000-8000-000000000123","version","3","lifecycleStatus","CLOSED","acceptanceStatus","ACCEPTED");
        ObjectNode template=json.createObjectNode().put("id",expected.get("id")).put("version",3).put("lifecycleStatus","CLOSED").put("acceptanceStatus","ACCEPTED").put("unrelated","前綴 \"quoted\" } { : \\ e\u0301 VECTOR 後綴");
        String encoded=json.writeValueAsString(template);var vectors=new ArrayList<String>();
        vectors.add(" \n"+json.writeValueAsString(template.deepCopy().put("unrelated","\u0000\u0000 \"quoted\" } { : \\ e\u0301 \u0000"))+" \n");
        // Odd JSON backslash counts encode a real NUL; even counts encode literal backslash-u0000 text.
        for(int backslashes=1;backslashes<=8;backslashes++)vectors.add(" \n"+encoded.replace("VECTOR","\\".repeat(backslashes)+"u0000")+" \n");
        String sql="WITH input AS (SELECT ?::json AS raw), lens AS (SELECT raw,logistics.management_ascii_metadata(raw) AS metadata FROM input) SELECT raw::text AS original,metadata->>'id' AS id,metadata->>'version' AS version,metadata->>'lifecycleStatus' AS \"lifecycleStatus\",metadata->>'acceptanceStatus' AS \"acceptanceStatus\" FROM lens";
        for(String raw:vectors){Map<String,Object> projection=jdbc.queryForMap(sql,raw);assertThat(projection.get("original")).isEqualTo(raw);for(var header:expected.entrySet())assertThat(projection.get(header.getKey())).as(header.getKey()).isEqualTo(header.getValue());}
        for(String field:expected.keySet())for(boolean poisonKey:List.of(false,true)){
            ObjectNode poisoned=template.deepCopy();if(poisonKey){JsonNode value=poisoned.remove(field);poisoned.set(field+"\u0000",value);}else poisoned.put(field,expected.get(field)+"\u0000");
            String raw=json.writeValueAsString(poisoned);Map<String,Object> projection=jdbc.queryForMap(sql,raw);assertThat(projection.get("original")).isEqualTo(raw);assertThat(projection.get(field)).as("true NUL in "+(poisonKey?"key ":"value ")+field).isNotEqualTo(expected.get(field));
        }
    }

    @Test void corruptedHistoricalConfirmationOrClosureReceiptNeverReturnsAlteredFactsOrBecomesFkBusinessError()throws Exception{
        Cohort flow=cohort(true);String closeKey=key();company(close(flow.source(),2,CLOSURE_REASON,closeKey),200,"0000");
        String confirmationOriginal=jdbc.queryForObject("SELECT original_data::text FROM logistics.tms_completion_confirmation_command WHERE completion_id=?",String.class,UUID.fromString(completionId(flow.firstCompletion())));
        String closureOriginal=jdbc.queryForObject("SELECT original_data::text FROM logistics.tom_order_closure_command WHERE order_id=?",String.class,uuid(flow.source().accepted()));
        for(String variant:List.of("REASON","ACTOR","TIME","POLICY")){
            ObjectNode changed=(ObjectNode)json.readTree(confirmationOriginal);ObjectNode fact=(ObjectNode)changed.get("confirmation");switch(variant){case "REASON"->fact.put("reason","被篡改的原始說明");case "ACTOR"->((ObjectNode)fact.get("actor")).put("displayName","被篡改的管理人");case "TIME"->fact.put("confirmedAt","2099-01-01T00:00:00Z");default->fact.put("policyMode","UNCONFIRMED");}
            replaceStoredReceipt("tms_completion_confirmation_command","tms_confirmation_command_immutable","completion_id",completionId(flow.firstCompletion()),json.writeValueAsString(changed));
            try{assertThat(company(admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON),flow.confirmationKeys().get(0)),500,"E0500").isNull()).isTrue();}finally{replaceStoredReceipt("tms_completion_confirmation_command","tms_confirmation_command_immutable","completion_id",completionId(flow.firstCompletion()),confirmationOriginal);}
        }
        for(String variant:List.of("REASON","ACTOR","TIME","POLICY","SOURCE_UNIT")){
            ObjectNode changed=(ObjectNode)json.readTree(closureOriginal);ObjectNode fact=(ObjectNode)changed.get("closure");switch(variant){case "REASON"->fact.put("reason","被篡改的原始結案說明");case "ACTOR"->((ObjectNode)fact.get("actor")).put("role","DISPATCHER");case "TIME"->fact.put("closedAt","2099-01-01T00:00:00Z");case "POLICY"->fact.put("policyMode","UNCONFIRMED");default->fact.put("sourceVersion",3);}
            replaceStoredReceipt("tom_order_closure_command","tom_closure_command_immutable","order_id",id(flow.source().accepted()),json.writeValueAsString(changed));
            try{assertThat(company(close(flow.source(),2,CLOSURE_REASON,closeKey),500,"E0500").isNull()).isTrue();}finally{replaceStoredReceipt("tom_order_closure_command","tom_closure_command_immutable","order_id",id(flow.source().accepted()),closureOriginal);}
        }
        company(close(flow.source(),2,CLOSURE_REASON,closeKey),200,"0000");
    }

    @ParameterizedTest @ValueSource(strings={"CONFIRM","CLOSE"})
    void sameKeyManagementCommandsTryLockBeforeQueueAndReplayExactlyOneCommittedFact(String action)throws Exception{
        Cohort flow=cohort(action.equals("CLOSE"));String route=action.equals("CLOSE")?closureBase(flow.source())+"/close":confirmationPath(flow.firstCompletion())+"/confirm",table=action.equals("CLOSE")?"tom_order_closure":"tms_completion_confirmation",commandKey=key(),token=admin.csrf();String body=json.writeValueAsString(action.equals("CLOSE")?fields("expectedVersion",2,"confirmClosure",true,"reason",CLOSURE_REASON):fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON));Browser another=login(administrator);String otherToken=another.csrf();pause(table,"BEFORE INSERT");
        try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
            advisory(held,true);var first=pool.submit(()->admin.send("POST",route,body,commandKey,token));try{awaitLock(table);long start=System.nanoTime();business(another.send("POST",route,body,commandKey,otherToken),"E2105","IDEMPOTENCY_IN_PROGRESS");assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(15));}finally{advisory(held,false);}JsonNode committed=company(first.get(20,TimeUnit.SECONDS),200,"0000");Map<String,String> before=allDigest();var replay=admin.send("POST",route,body,commandKey,admin.csrf());assertThat(company(replay,200,"0000")).isEqualTo(committed);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(allDigest()).isEqualTo(before);
        }finally{unpause(table);}
    }

    @ParameterizedTest @ValueSource(strings={"CONFIRM","CLOSE"})
    void differentKeysCompeteForOneAdministrativeCounterOrOneTomVersion(String action)throws Exception{
        Cohort flow=cohort(action.equals("CLOSE"));String route=action.equals("CLOSE")?closureBase(flow.source())+"/close":confirmationPath(flow.firstCompletion())+"/confirm",token=admin.csrf();String body=json.writeValueAsString(action.equals("CLOSE")?fields("expectedVersion",2,"confirmClosure",true,"reason",CLOSURE_REASON):fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON));List<HttpResponse<String>> replies;
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()){
            held.setAutoCommit(false);canonical(held);var first=pool.submit(()->admin.send("POST",route,body,key(),token));var second=pool.submit(()->admin.send("POST",route,body,key(),token));try{awaitLockCount("upms_lock",2);}finally{held.rollback();}replies=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
        assertThat(replies.stream().filter(reply->reply.statusCode()==200).count()).isEqualTo(1);for(var reply:replies)if(reply.statusCode()==200)company(reply,200,"0000");else{JsonNode conflict=business(reply,"E2101","VERSION_CONFLICT");shape(conflict,List.of("reason",action.equals("CLOSE")?"actualOrderVersion":"actualConfirmationVersion"));}
        assertThat(count(action.equals("CLOSE")?"tom_order_closure":"tms_completion_confirmation")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"CONFIRM","CONFIRM_HISTORY","CLOSE","CLOSE_HISTORY"})
    void managementCommandsAndHistoricalReadsQueuedAfterLogoutCannotUseAnEarlierCapturedSession(String action)throws Exception{
        Cohort flow=cohort(action.startsWith("CLOSE"));String token=admin.csrf();Map<String,String> before=allDigest();List<HttpResponse<String>> replies;
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()){
            held.setAutoCommit(false);canonical(held);var logout=pool.submit(()->admin.send("POST","/auth/logout","{}",null,token));try{awaitLockCount("upms_lock",1);var pending=pool.submit(()->switch(action){case "CONFIRM"->admin.send("POST",confirmationPath(flow.firstCompletion())+"/confirm",json.writeValueAsString(fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON)),key(),token);case "CLOSE"->admin.send("POST",closureBase(flow.source())+"/close",json.writeValueAsString(fields("expectedVersion",2,"confirmClosure",true,"reason",CLOSURE_REASON)),key(),token);case "CONFIRM_HISTORY"->admin.get(confirmationPath(flow.firstCompletion())+"/confirmation/history");default->admin.get(closureBase(flow.source())+"/closure/history");});try{awaitLockCount("upms_lock",2);}finally{held.rollback();}replies=List.of(logout.get(20,TimeUnit.SECONDS),pending.get(20,TimeUnit.SECONDS));}finally{held.rollback();}
        }
        company(replies.get(0),200,"0000");assertThat(company(replies.get(1),401,"E0401").isNull()).isTrue();assertThat(allDigest()).isEqualTo(before);
    }

    private void replaceStoredReceipt(String table,String trigger,String column,String id,String raw){jdbc.execute("ALTER TABLE logistics."+table+" DISABLE TRIGGER "+trigger);try{jdbc.update("UPDATE logistics."+table+" SET original_data=?::json WHERE "+column+"=?",raw,UUID.fromString(id));}finally{jdbc.execute("ALTER TABLE logistics."+table+" ENABLE TRIGGER "+trigger);}}

    private static final String MANAGEMENT_REASON=" 管理\u0000確認 e\u0301 \n ",CLOSURE_REASON=" 整單\u0000結案 e\u0301 \n ";
    private static final List<String> CONFIRMATION_SUMMARY_FIELDS=List.of("completionId","assignmentId","taskId","taskNo","orderId","orderNo","sourceOrderVersion","completedAt","version","state","confirmedAt");
    private static final List<String> REVIEW_FIELDS=List.of("orderId","sourceOrderVersion","taskId","taskNo","requiredTask","assignmentId","completionId","executionPhase","executionVersion","completionVerified","confirmationVersion","confirmedAt","blockingExceptionCount","effectiveOccupationCount");
    private record OrderSource(JsonNode accepted,JsonNode created,JsonNode replaced,ObjectNode draft,Map<String,Object> putBody,String createKey,String putKey,String acceptKey){}
    private record Cohort(OrderSource source,Proof first,Proof second,JsonNode firstCompletion,JsonNode secondCompletion,List<JsonNode> confirmations,List<String> confirmationKeys){}
    private OrderSource orderSource(String type)throws Exception{
        JsonNode customer=company(admin.write("POST","/crm/customers",fields("customerCode","SYN-CLOSURE-"+key().toUpperCase(java.util.Locale.ROOT),"name","原始合成客戶名稱"),null),201,"0000");
        ObjectNode draft=(ObjectNode)TomContract.example("graphCases","DEMO-"+type+"-CONTRACT").get("draft").deepCopy();draft.put("customerId",id(customer)).put("remark"," 原始\u0000結案來源 e\u0301 ");
        for(JsonNode task:draft.get("tasks"))((ObjectNode)task).put("taskKind","SYNTHETIC_TMS_IT_"+task.get("clientRef").asString());
        ((ObjectNode)draft.get("tasks").get(1)).putNull("requiredTask");((ObjectNode)draft.get("items").get(0)).put("grossWeight","0.0000000000000000001");
        ((ObjectNode)draft.get("tasks").get(0)).put("pickupAddress"," 原始\u0000地址 e\u0301 ").put("plannedStartAt","1500-01-02T00:00:00.123456+23:59").put("plannedEndAt","1500-01-02T01:00:00.123456+23:59");
        String createKey=key(),putKey=key(),acceptKey=key();JsonNode created=tom(admin.write("POST","/orders",draft,createKey),201);var putBody=fields("expectedVersion",0,"draft",draft.deepCopy());JsonNode replaced=tom(admin.write("PUT","/orders/"+id(created),putBody,putKey),200);JsonNode accepted=tom(admin.write("POST","/orders/"+id(created)+"/accept",fields("expectedVersion",1),acceptKey),200);
        OrderSource source=new OrderSource(accepted,created,replaced,draft.deepCopy(),putBody,createKey,putKey,acceptKey);orderSources.put(id(accepted),source);return source;
    }
    private Proof taskProof(OrderSource source,UUID task)throws Exception{
        Map<String,Object> selection=fields("expectedGeneration",0,"driverId",id(driver),"mainVehicleId",id(main),"trailerId",null);String managerKey=key();JsonNode managerResult=company(admin.write("POST","/tms/tasks/"+task+"/dispatch",selection,managerKey),200,"0000"),assignment=managerResult.get("assignment");
        String departKey=key(),startKey=key(),readyKey=key();JsonNode departure=company(driverBrowser.write("POST",departPath(assignment),fields("expectedVersion",0),departKey),200,"0000");Ready ready=new Ready(assignment,departure);
        JsonNode one=company(upload(ready,png(1,1,0xff112233),"1",key()),200,"0000");company(progress(ready,1,"IN_PROGRESS",List.of(id(one)),startKey),200,"0000");JsonNode two=company(upload(ready,png(1,1,0xff334455),"2",key()),200,"0000");List<String> ids=List.of(id(two));company(progress(ready,2,"AWAITING_COMPLETION",ids,readyKey),200,"0000");return new Proof(ready,source.accepted(),managerResult,managerKey,selection,departKey,startKey,readyKey,two,ids);
    }
    private Cohort cohort(boolean confirmations)throws Exception{
        OrderSource source=orderSource("LOCAL");UUID first=null,second=null;for(JsonNode task:source.accepted().get("tasks"))if(task.get("predecessorRefs").isEmpty())first=uuid(task);else second=uuid(task);assertThat(first).isNotNull();assertThat(second).isNotNull();Proof one=taskProof(source,first);JsonNode completedOne=company(complete(one,3,one.ids(),key()),200,"0000");Proof two=taskProof(source,second);JsonNode completedTwo=company(complete(two,3,two.ids(),key()),200,"0000");
        var results=new ArrayList<JsonNode>();var keys=new ArrayList<String>();if(confirmations)for(JsonNode completed:List.of(completedOne,completedTwo)){String key=key();results.add(confirm(completed,0,MANAGEMENT_REASON,key));keys.add(key);}return new Cohort(source,one,two,completedOne,completedTwo,List.copyOf(results),List.copyOf(keys));
    }
    private String completionId(JsonNode completed){return completed.get("completion").get("id").asString();}
    private String confirmationPath(JsonNode completed){return "/tms/completions/"+completionId(completed);}
    private JsonNode confirm(JsonNode completed,long version,String reason,String key)throws Exception{return company(admin.write("POST",confirmationPath(completed)+"/confirm",fields("expectedVersion",version,"confirmCompletion",true,"reason",reason),key),200,"0000");}
    private String closureBase(OrderSource source){return "/orders/"+id(source.accepted());}
    private HttpResponse<String> close(OrderSource source,long version,String reason,String key)throws Exception{return admin.write("POST",closureBase(source)+"/close",fields("expectedVersion",version,"confirmClosure",true,"reason",reason),key);}
    private void confirmationResult(JsonNode result,JsonNode completed,OrderSource source){shape(result,List.of("completionId","assignmentId","taskId","sourceOrderVersion","version","confirmation"));assertThat(result.get("completionId").asString()).isEqualTo(completionId(completed));assertThat(result.get("assignmentId")).isEqualTo(completed.get("assignmentId"));assertThat(result.get("taskId")).isEqualTo(completed.get("taskId"));assertThat(result.get("sourceOrderVersion")).isEqualTo(source.accepted().get("version"));assertThat(result.get("version").asLong()).isEqualTo(1);JsonNode fact=result.get("confirmation");shape(fact,List.of("id","version","confirmedAt","policyMode","reason","actor"));assertThat(fact.get("version").asLong()).isEqualTo(1);assertThat(fact.get("reason").asString()).isEqualTo(MANAGEMENT_REASON);assertThat(fact.get("policyMode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");shape(fact.get("actor"),List.of("role","displayName"));assertThat(fact.get("actor").get("role").asString()).isEqualTo("ADMIN");}
    private void closureResult(JsonNode result,OrderSource source){shape(result,List.of("orderId","orderNo","lifecycleStatus","acceptanceStatus","version","closure"));assertThat(result.get("orderId").asString()).isEqualTo(id(source.accepted()));assertThat(result.get("orderNo")).isEqualTo(source.accepted().get("orderNo"));assertThat(result.get("lifecycleStatus").asString()).isEqualTo("CLOSED");assertThat(result.get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");assertThat(result.get("version").asLong()).isEqualTo(source.accepted().get("version").asLong()+1);JsonNode witness=result.get("closure");shape(witness,List.of("id","sourceVersion","version","closedAt","policyMode","managerConfirmed","reason","taskCount","actor"));assertThat(witness.get("sourceVersion")).isEqualTo(source.accepted().get("version"));assertThat(witness.get("version")).isEqualTo(result.get("version"));assertThat(witness.get("taskCount").asLong()).isEqualTo(2);assertThat(witness.get("reason").asString()).isEqualTo(CLOSURE_REASON);assertThat(witness.get("managerConfirmed").asBoolean()).isTrue();assertThat(witness.get("policyMode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");shape(witness.get("actor"),List.of("role","displayName"));}
    private Map<String,String> priorFacts(){var values=allDigest();for(String table:List.of("tom_order","order_core","order_draft_revision","tom_order_closure","tom_order_closure_task","tom_order_closure_command"))values.remove(table);return values;}

    private static final List<String> COMPLETION_DETAIL_FIELDS=List.of("assignmentId","taskId","taskNo","item","taskKind","pickupName","pickupAddress","deliveryName","deliveryAddress","siteContact","plannedStartAt","plannedEndAt","assignedAt","policyMode","execution","completion");
    private record Proof(Ready ready,JsonNode order,JsonNode managerResult,String managerKey,Map<String,Object> managerBody,String departureKey,String startKey,String readyKey,JsonNode extraPhoto,List<String> ids){}
    private Proof proof(boolean trailer,int selected)throws Exception{
        JsonNode order=acceptedOrder("LOCAL");UUID task=null;for(JsonNode candidate:order.get("tasks"))if(candidate.get("predecessorRefs").isEmpty())task=uuid(candidate);assertThat(task).isNotNull();
        JsonNode trailerVehicle=null;if(trailer){UUID category=null;for(JsonNode item:company(admin.get("/adm/categories?size=100"),200,"0000").get("content"))if(item.get("code").asString().equals("VEHICLE_TYPE"))category=uuid(item);JsonNode type=company(admin.write("POST","/adm/categories/"+category+"/items",fields("code","SYNTHETIC_TMS_IT_TRAILER","label","合成完成拖板","enabled",true,"sortOrder",2),null),201,"0000");trailerVehicle=vehicle(uuid(type));}
        Map<String,Object> selection=fields("expectedGeneration",0,"driverId",id(driver),"mainVehicleId",id(main),"trailerId",trailerVehicle==null?null:id(trailerVehicle));String managerKey=key();JsonNode managerResult=company(admin.write("POST","/tms/tasks/"+task+"/dispatch",selection,managerKey),200,"0000"),assignment=managerResult.get("assignment");
        String departKey=key();JsonNode departure=company(driverBrowser.write("POST",departPath(assignment),fields("expectedVersion",0),departKey),200,"0000");Ready ready=new Ready(assignment,departure);
        JsonNode first=company(upload(ready,png(1,1,0xff223344),"1",key()),200,"0000");String startKey=key();company(progress(ready,1,"IN_PROGRESS",List.of(id(first)),startKey),200,"0000");
        JsonNode second=company(upload(ready,png(1,1,0xff445566),"2",key()),200,"0000"),extra=company(upload(ready,png(1,1,0xff667788),"2",key()),200,"0000");
        var ids=new ArrayList<String>();ids.add(id(second));if(selected==2)ids.add(id(extra));ids.sort(String::compareTo);String readyKey=key();company(progress(ready,2,"AWAITING_COMPLETION",ids,readyKey),200,"0000");
        return new Proof(ready,order,managerResult,managerKey,selection,departKey,startKey,readyKey,extra,List.copyOf(ids));
    }
    private String completionBase(Proof proof){return path(proof.ready());}
    private Map<String,Object> completeBody(long version,List<String> ids){return fields("expectedVersion",version,"evidenceIds",ids,"confirmSafeRelease",true);}
    private HttpResponse<String> complete(Proof proof,long version,List<String> ids,String key)throws Exception{return driverBrowser.write("POST",completionBase(proof)+"/complete",completeBody(version,ids),key);}
    private void completionAdvice(JsonNode value,Proof proof){shape(value,List.of("assignmentId","taskId","execution","mode","canComplete","blocker","requirements","requiredEvidenceIds"));assertThat(value.get("assignmentId").asString()).isEqualTo(id(proof.ready().assignment()));assertThat(value.get("canComplete").asBoolean()).isTrue();assertThat(value.get("blocker").isNull()).isTrue();assertThat(value.get("mode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");var ids=new ArrayList<String>();value.get("requiredEvidenceIds").forEach(id->ids.add(id.asString()));assertThat(ids).containsExactlyElementsOf(proof.ids());JsonNode rule=value.get("requirements");shape(rule,List.of("evidenceBasis","minimumCount","allowedMediaTypes","maxBytes","maxPixels","requiresSafeReleaseConfirmation"));assertThat(rule.get("evidenceBasis").asString()).isEqualTo("LATEST_AWAITING_TRANSITION");assertThat(rule.get("minimumCount").asInt()).isEqualTo(1);assertThat(rule.get("allowedMediaTypes").get(0).asString()).isEqualTo("image/png");assertThat(rule.get("maxBytes").asInt()).isEqualTo(65536);assertThat(rule.get("maxPixels").asLong()).isEqualTo(4096);assertThat(rule.get("requiresSafeReleaseConfirmation").asBoolean()).isTrue();}
    private void completed(JsonNode value,Proof proof,Instant at){shape(value,List.of("assignmentId","taskId","execution","completion"));assertThat(value.get("assignmentId").asString()).isEqualTo(id(proof.ready().assignment()));assertThat(value.get("taskId")).isEqualTo(proof.ready().assignment().get("taskId"));shape(value.get("execution"),List.of("phase","version","departedAt"));assertThat(value.get("execution").get("phase").asString()).isEqualTo("COMPLETED");assertThat(value.get("execution").get("version").asLong()).isEqualTo(4);assertThat(value.get("execution").get("departedAt")).isEqualTo(proof.ready().departure().get("execution").get("departedAt"));JsonNode witness=value.get("completion");shape(witness,List.of("id","completedAt","releasedAt","policyMode","safeReleaseConfirmed"));assertThat(Instant.parse(witness.get("completedAt").asString())).isEqualTo(at);assertThat(witness.get("releasedAt")).isEqualTo(witness.get("completedAt"));assertThat(witness.get("policyMode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");assertThat(witness.get("safeReleaseConfirmed").asBoolean()).isTrue();}
    private long effectiveReservations(){return jdbc.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL AND completion_id IS NULL",Long.class);}
    private Map<String,String> legacyDigest(){var values=dispatchAndSourceDigest();values.put("tms_resource_reservation",jdbc.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY id)::text,'[]')) FROM (SELECT id,assignment_id,driver_id,vehicle_id,resource_kind,resource_id,position,acquired_at,released_at,release_reason FROM logistics.tms_resource_reservation) t",String.class));return values;}

    private static final String REPORT=" 原始\u0000異常 e\u0301 \n ";
    private static final String REQUEST=" 請補件\u0000指示 e\u0301 \n ";
    private static final String SUBMIT=" 原始\u0000補件說明 e\u0301 \n ";
    private static final String RESOLVE=" 原始\u0000處置說明 e\u0301 \n ";
    private String selfBase(Ready ready){return path(ready)+"/exceptions";}
    private String selfPath(Ready ready,JsonNode record){return selfBase(ready)+"/"+id(record);}
    private String managerPath(JsonNode record){return "/tms/exceptions/"+id(record);}
    private String submitPath(Ready ready,JsonNode record,String request){return selfPath(ready,record)+"/supplement-requests/"+request+"/submit";}
    private JsonNode report(Ready ready,long executionVersion,String description,String key)throws Exception{return company(driverBrowser.write("POST",selfBase(ready),fields("expectedExecutionVersion",executionVersion,"description",description),key),200,"0000");}
    private JsonNode request(JsonNode record,long version,String reason,String key)throws Exception{return company(admin.write("POST",managerPath(record)+"/supplement-requests",fields("expectedVersion",version,"reason",reason),key),200,"0000");}
    private JsonNode resolve(JsonNode record,long version,String reason,String key)throws Exception{return company(admin.write("POST",managerPath(record)+"/resolve",fields("expectedVersion",version,"reason",reason),key),200,"0000");}
    private JsonNode submit(Browser browser,Ready ready,JsonNode record,String request,long version,String note,List<String> evidence,String key)throws Exception{return company(browser.write("POST",submitPath(ready,record,request),fields("expectedVersion",version,"note",note,"evidenceIds",evidence),key),200,"0000");}
    private HttpResponse<String> supplementUpload(Browser browser,Ready ready,JsonNode record,String request,byte[] bytes,String exceptionVersion,String executionVersion,String key,String token)throws Exception{
        if(token==null)token=browser.csrf();var builder=supplementRequest(browser,ready,record,request,bytes,exceptionVersion,executionVersion,key,token,"image/png");return browser.client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder supplementRequest(Browser browser,Ready ready,JsonNode record,String request,byte[] bytes,String exceptionVersion,String executionVersion,String key,String token,String media){var result=browser.request("POST",selfPath(ready,record)+"/supplement-requests/"+request+"/evidence",bytes,media,token);if(exceptionVersion!=null)result.header("X-Exception-Version",exceptionVersion);if(executionVersion!=null)result.header("X-Execution-Version",executionVersion);if(key!=null)result.header("Idempotency-Key",key);return result;}
    private HttpResponse<byte[]> managerContent(Browser browser,JsonNode record,String evidence)throws Exception{return browser.client.send(browser.request("GET",managerPath(record)+"/evidence/"+evidence+"/content",null,null,null).setHeader("Accept","image/png").build(),HttpResponse.BodyHandlers.ofByteArray());}
    private void exceptionRecord(JsonNode value,Ready ready,String status,long version){shape(value,RECORD_FIELDS);assertThat(value.get("assignmentId").asString()).isEqualTo(id(ready.assignment()));assertThat(value.get("taskId")).isEqualTo(ready.assignment().get("taskId"));assertThat(value.get("taskNo")).isEqualTo(ready.assignment().get("source").get("taskNo"));assertThat(value.get("reportedPhase").asString()).isIn("DEPARTED","IN_PROGRESS","AWAITING_COMPLETION");assertThat(value.get("reportedExecutionVersion").asLong()).isGreaterThanOrEqualTo(1);assertThat(value.get("status").asString()).isEqualTo(status);assertThat(value.get("version").asLong()).isEqualTo(version);assertThat(value.get("blocking").asBoolean()).isEqualTo(!status.equals("RESOLVED"));assertThat(value.get("activeRequest").isNull()).isEqualTo(!status.equals("NEEDS_SUPPLEMENT"));assertThat(value.get("resolvedAt").isNull()).isEqualTo(!status.equals("RESOLVED"));assertThat(value.get("resolutionNote").isNull()).isEqualTo(!status.equals("RESOLVED"));}
    private void detail(JsonNode value,JsonNode expected,boolean manager){shape(value,List.of("exception","execution","actions"));assertThat(value.get("exception")).isEqualTo(expected);shape(value.get("execution"),List.of("phase","version","departedAt"));JsonNode actions=value.get("actions");shape(actions,List.of("mode","requestSupplement","resolve","uploadSupplement","submitSupplement","requirements"));for(String action:List.of("requestSupplement","resolve","uploadSupplement","submitSupplement")){JsonNode available=actions.get(action);shape(available,List.of("allowed","blocker"));assertThat(available.get("allowed").asBoolean()).isEqualTo(available.get("blocker").isNull());if(available.get("allowed").asBoolean())assertThat(actions.get("mode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");}for(String forbidden:manager?List.of("uploadSupplement","submitSupplement"):List.of("requestSupplement","resolve")){assertThat(actions.get(forbidden).get("allowed").asBoolean()).isFalse();assertThat(actions.get(forbidden).get("blocker").asString()).isEqualTo("FORBIDDEN");}}
    private record Cycle(Ready ready,JsonNode report,JsonNode requested,JsonNode upload,JsonNode submitted,String requestKey,String uploadKey,String submitKey,byte[] bytes){String requestId(){return requested.get("activeRequest").get("id").asString();}String photoId(){return upload.get("evidence").get("id").asString();}}
    private Cycle cycle()throws Exception{Ready ready=ready("LOCAL");JsonNode reported=report(ready,1,REPORT,key());String requestKey=key(),uploadKey=key(),submitKey=key();JsonNode requested=request(reported,0,REQUEST,requestKey);byte[] bytes=png(1,1,0xff112233);String requestId=id(requested.get("activeRequest"));JsonNode photo=company(supplementUpload(driverBrowser,ready,reported,requestId,bytes,"1","1",uploadKey,null),200,"0000");JsonNode submitted=submit(driverBrowser,ready,reported,requestId,2,SUBMIT,List.of(id(photo.get("evidence"))),submitKey);return new Cycle(ready,reported,requested,photo,submitted,requestKey,uploadKey,submitKey,bytes);}
    private record Step(String action,Ready ready,JsonNode record,String requestId,long expectedVersion,String key,byte[] bytes,String photoId){}
    private Step step(String action)throws Exception{if(action.equals("RESOLVE")){Cycle cycle=cycle();return new Step(action,cycle.ready(),cycle.report(),cycle.requestId(),3,key(),cycle.bytes(),cycle.photoId());}Ready ready=ready("LOCAL");if(action.equals("REPORT"))return new Step(action,ready,null,null,0,key(),null,null);JsonNode reported=report(ready,1,REPORT,key());if(action.equals("REQUEST"))return new Step(action,ready,reported,null,0,key(),null,null);String request=id(request(reported,0,REQUEST,key()).get("activeRequest"));byte[] bytes=png(1,1,0xff112233);if(action.equals("UPLOAD"))return new Step(action,ready,reported,request,1,key(),bytes,null);JsonNode photo=company(supplementUpload(driverBrowser,ready,reported,request,bytes,"1","1",key(),null),200,"0000");return new Step(action,ready,reported,request,2,key(),bytes,id(photo.get("evidence")));}
    private HttpResponse<String> execute(Step step)throws Exception{return switch(step.action()){case "REPORT"->driverBrowser.write("POST",selfBase(step.ready()),fields("expectedExecutionVersion",1,"description",REPORT),step.key());case "REQUEST"->admin.write("POST",managerPath(step.record())+"/supplement-requests",fields("expectedVersion",0,"reason",REQUEST),step.key());case "UPLOAD"->supplementUpload(driverBrowser,step.ready(),step.record(),step.requestId(),step.bytes(),"1","1",step.key(),null);case "SUBMIT"->driverBrowser.write("POST",submitPath(step.ready(),step.record(),step.requestId()),fields("expectedVersion",2,"note",SUBMIT,"evidenceIds",List.of(step.photoId())),step.key());default->admin.write("POST",managerPath(step.record())+"/resolve",fields("expectedVersion",3,"reason",RESOLVE),step.key());};}
    private ExceptionQueries.Blocking blocking(Ready ready){return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{jdbc.queryForObject("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE",Integer.class);jdbc.queryForObject("SELECT id FROM logistics.fms_driver WHERE id=? FOR UPDATE",UUID.class,uuid(driver));taskSources.lockCurrentForDispatch(UUID.fromString(ready.assignment().get("taskId").asString()));return blockers.lockBlockingForAssignment(uuid(ready.assignment()));});}
    private HttpResponse<byte[]> queuedException(Browser browser,Cycle cycle,String action,String token)throws Exception{HttpRequest.Builder builder;switch(action){case "DRIVER_REPORT"->builder=browser.request("POST",selfBase(cycle.ready()),json.writeValueAsBytes(fields("expectedExecutionVersion",1,"description",REPORT)),"application/json",token).header("Idempotency-Key",key());case "DRIVER_UPLOAD_REPLAY"->builder=supplementRequest(browser,cycle.ready(),cycle.report(),cycle.requestId(),cycle.bytes(),"1","1",cycle.uploadKey(),token,"image/png");case "DRIVER_SUBMIT_REPLAY"->builder=browser.request("POST",submitPath(cycle.ready(),cycle.report(),cycle.requestId()),json.writeValueAsBytes(fields("expectedVersion",2,"note",SUBMIT,"evidenceIds",List.of(cycle.photoId()))),"application/json",token).header("Idempotency-Key",cycle.submitKey());case "MANAGER_REQUEST"->builder=browser.request("POST",managerPath(cycle.report())+"/supplement-requests",json.writeValueAsBytes(fields("expectedVersion",3,"reason",REQUEST)),"application/json",token).header("Idempotency-Key",key());case "MANAGER_CONTENT"->builder=browser.request("GET",managerPath(cycle.report())+"/evidence/"+cycle.photoId()+"/content",null,null,null).setHeader("Accept","image/png");default->builder=browser.request("GET",managerPath(cycle.report())+"/history",null,null,null);}return browser.client.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());}

    private record Ready(JsonNode assignment,JsonNode departure){}
    private Ready ready(String type)throws Exception{JsonNode assignment=assigned(type,driver,main);JsonNode departed=company(driverBrowser.write("POST",departPath(assignment),fields("expectedVersion",0),key()),200,"0000");return new Ready(assignment,departed);}
    private String path(Ready ready){return ownPath(ready.assignment());}
    private HttpResponse<String> upload(Ready ready,byte[] bytes,String version,String key)throws Exception{return rawUpload(driverBrowser,ready,bytes,version,key,"image/png",null);}
    private HttpResponse<String> rawUpload(Browser browser,Ready ready,byte[] bytes,String version,String key,String media,String encoding)throws Exception{
        var request=photoRequest(browser,ready,bytes,version,key,media,browser.csrf());if(encoding!=null)request.header("Content-Encoding",encoding);
        return browser.client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> rawUploadWithToken(Browser browser,Ready ready,byte[] bytes,String version,String key,String token)throws Exception{return browser.client.send(photoRequest(browser,ready,bytes,version,key,"image/png",token).build(),HttpResponse.BodyHandlers.ofString());}
    private HttpRequest.Builder photoRequest(Browser browser,Ready ready,byte[] bytes,String version,String key,String media,String token){var request=browser.request("POST",path(ready)+"/evidence",bytes,media,token);if(version!=null)request.header("X-Execution-Version",version);if(key!=null)request.header("Idempotency-Key",key);return request;}
    private HttpResponse<String> progress(Ready ready,long version,String phase,List<String> ids,String key)throws Exception{return driverBrowser.write("POST",path(ready)+"/progress",fields("expectedVersion",version,"targetPhase",phase,"evidenceIds",ids),key);}
    private HttpResponse<byte[]> content(Browser browser,Ready ready,String id)throws Exception{return browser.client.send(browser.request("GET",path(ready)+"/evidence/"+id+"/content",null,null,null).setHeader("Accept","image/png").build(),HttpResponse.BodyHandlers.ofByteArray());}
    private void policyView(JsonNode value,Ready ready,String next){shape(value,List.of("assignmentId","taskId","execution","mode","nextPhase","requirements","blocker"));assertThat(value.get("assignmentId").asString()).isEqualTo(id(ready.assignment()));assertThat(value.get("taskId")).isEqualTo(ready.assignment().get("taskId"));assertThat(value.get("mode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");assertThat(value.get("nextPhase").asString()).isEqualTo(next);assertThat(value.get("blocker").isNull()).isTrue();JsonNode rule=value.get("requirements");shape(rule,List.of("minimumCount","allowedMediaTypes","maxBytes","maxPixels"));assertThat(rule.get("minimumCount").asInt()).isEqualTo(1);assertThat(rule.get("allowedMediaTypes")).hasSize(1);assertThat(rule.get("allowedMediaTypes").get(0).asString()).isEqualTo("image/png");assertThat(rule.get("maxBytes").asInt()).isEqualTo(65536);assertThat(rule.get("maxPixels").asLong()).isEqualTo(4096);}
    private void evidence(JsonNode value,Ready ready,String phase,long version,byte[] original)throws Exception{shape(value,EVIDENCE_FIELDS);assertThat(value.get("assignmentId").asString()).isEqualTo(id(ready.assignment()));assertThat(value.get("taskId")).isEqualTo(ready.assignment().get("taskId"));assertThat(value.get("phase").asString()).isEqualTo(phase);assertThat(value.get("executionVersion").asLong()).isEqualTo(version);assertThat(value.get("mediaType").asString()).isEqualTo("image/png");assertThat(value.get("byteSize").asLong()).isEqualTo(original.length);assertThat(value.get("sha256").asString()).isEqualTo(hash(original));assertThat(value.get("status").asString()).isEqualTo("VERIFIED");}
    private void progressResult(JsonNode value,Ready ready,String phase,long version){shape(value,List.of("assignmentId","taskId","execution"));assertThat(value.get("assignmentId").asString()).isEqualTo(id(ready.assignment()));assertThat(value.get("taskId")).isEqualTo(ready.assignment().get("taskId"));shape(value.get("execution"),List.of("phase","version","departedAt"));assertThat(value.get("execution").get("phase").asString()).isEqualTo(phase);assertThat(value.get("execution").get("version").asLong()).isEqualTo(version);assertThat(value.get("execution").get("departedAt")).isEqualTo(ready.departure().get("execution").get("departedAt"));}
    private void assertPrivateContent(HttpResponse<byte[]> response,byte[] expected)throws Exception{assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(expected);assertThat(response.headers().firstValue("Content-Type")).contains("image/png");assertThat(response.headers().firstValue("Content-Length")).contains(Integer.toString(expected.length));assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");assertThat(response.headers().firstValue("Content-Disposition")).contains("inline; filename=\"evidence.png\"");privateHeaders(response);for(String header:List.of("ETag","Last-Modified","Accept-Ranges","Location","Content-Location"))assertThat(response.headers().firstValue(header)).isEmpty();assertThat(hash(response.body())).isEqualTo(hash(expected));}
    private void privateHeaders(HttpResponse<?> response){String cache=response.headers().firstValue("Cache-Control").orElse("").toLowerCase(java.util.Locale.ROOT);assertThat(cache).contains("no-store","private");}
    private JsonNode binaryError(HttpResponse<byte[]> response,int status,String code){assertThat(response.statusCode()).isEqualTo(status);assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");JsonNode body=json.readTree(response.body());shape(body,List.of("code","message","data","timestamp"));assertThat(body.get("code").asString()).isEqualTo(code);assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));if(response.uri().getPath().startsWith("/api/v1/tms/my-tasks/")||response.uri().getPath().startsWith("/api/v1/tms/exceptions"))privateHeaders(response);return body.get("data");}
    private void binaryBusiness(HttpResponse<byte[]> response,String code,String reason){assertThat(binaryError(response,409,code).get("reason").asString()).isEqualTo(reason);}
    private HttpResponse<byte[]> queuedEvidenceRequest(Ready ready,JsonNode photo,String operation,String token)throws Exception{
        String suffix=switch(operation){case "POLICY"->"/progress-policy";case "PROGRESS"->"/progress";case "CONTENT"->"/evidence/"+id(photo)+"/content";default->"/evidence";};
        HttpRequest.Builder request;
        if(operation.equals("UPLOAD"))request=photoRequest(driverBrowser,ready,png(1,1,0xff998877),"1",key(),"image/png",token);
        else if(operation.equals("PROGRESS"))request=driverBrowser.request("POST",path(ready)+suffix,json.writeValueAsBytes(fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(photo)))),"application/json",token).header("Idempotency-Key",key());
        else request=driverBrowser.request("GET",path(ready)+suffix,null,null,null);
        if(operation.equals("CONTENT"))request.setHeader("Accept","image/png");
        return driverBrowser.client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private byte[] png(int width,int height,int argb)throws Exception{var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setRGB(x,y,argb);try(var output=new java.io.ByteArrayOutputStream()){assertThat(javax.imageio.ImageIO.write(image,"png",output)).isTrue();return output.toByteArray();}}
    private byte[] paddedPng(int target)throws Exception{byte[] original=png(1,1,0xff123456);int payload=target-original.length-12;assertThat(payload).isPositive();try(var output=new java.io.ByteArrayOutputStream();var data=new java.io.DataOutputStream(output)){data.write(original,0,original.length-12);data.writeInt(payload);byte[] kind="vpAg".getBytes(StandardCharsets.US_ASCII),padding=new byte[payload];data.write(kind);data.write(padding);var crc=new java.util.zip.CRC32();crc.update(kind);crc.update(padding);data.writeInt((int)crc.getValue());data.write(original,original.length-12,12);return output.toByteArray();}}
    private String hash(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void noEvidence(){for(String table:EVIDENCE_TABLES)assertThat(count(table)).as("No committed metadata/object/link/receipt in %s",table).isZero();}
    private void failAt(String table,String event){jdbc.execute("CREATE FUNCTION logistics.test_execution_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Synthetic evidence transaction failure'; END $$");jdbc.execute("CREATE TRIGGER test_execution_fail "+event+" ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_fail()");}

    private JsonNode assigned(String type,JsonNode selectedDriver,JsonNode selectedMain) throws Exception {
        JsonNode order=acceptedOrder(type);UUID task=null;
        for(JsonNode candidate:order.get("tasks"))if(candidate.get("predecessorRefs").isEmpty())task=uuid(candidate);
        assertThat(task).isNotNull();
        return company(admin.write("POST","/tms/tasks/"+task+"/dispatch",fields("expectedGeneration",0,"driverId",id(selectedDriver),"mainVehicleId",id(selectedMain),"trailerId",null),key()),200,"0000").get("assignment");
    }
    private JsonNode acceptedOrder(String type)throws Exception{return orderSource(type).accepted();}
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
    private Map<String,String> allDigest(){var result=dispatchAndSourceDigest();for(String table:List.of("tms_completion_confirmation","tms_completion_confirmation_command","tom_order_closure","tom_order_closure_command"))result.put(table,digest(table,"id"));result.put("tom_order_closure_task",digest("tom_order_closure_task","task_id"));for(String table:List.of("tms_completion","tms_completion_command"))result.put(table,digest(table,"id"));for(String table:EXCEPTION_TABLES)result.put(table,digest(table,"id"));for(String table:EVIDENCE_TABLES)result.put(table,digest(table,table.equals("tms_execution_event_evidence")?"event_id,evidence_id":"id"));for(String table:EXECUTION_TABLES)result.put(table,digest(table,table.equals("tms_execution")?"assignment_id":"id"));return result;}
    private Map<String,String> dispatchAndSourceDigest(){var result=new LinkedHashMap<String,String>();for(String table:DISPATCH_TABLES)result.put(table,digest(table,table.equals("tms_task_dispatch")?"task_id":"id"));for(String table:List.of("fms_driver","fms_vehicle","order_core","tom_order","cargo_item","transport_task","task_dependency","order_draft_revision","api_idempotency","order_acceptance_event","crm_customer","crm_contact","crm_address"))result.put(table,digest(table,table.equals("tom_order")?"order_id":table.equals("task_dependency")?"task_id,predecessor_task_id":"id"));return result;}
    private String digest(String table,String ordering){return jdbc.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+ordering+")::text,'[]')) FROM logistics."+table+" t",String.class);}
    private void shape(JsonNode value,List<String> expected){assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);}
    private JsonNode company(HttpResponse<String> reply,int status,String code){if(reply.uri().getPath().matches("/api/v1/tms/my-tasks/[^/]+/(progress-policy|progress|evidence|exceptions|complete|completion|completion-policy)(/.*)?")||reply.uri().getPath().startsWith("/api/v1/tms/exceptions")||reply.uri().getPath().startsWith("/api/v1/tms/completions")||reply.uri().getPath().matches("/api/v1/orders/(closure-worklist|[^/]+/(closure|close)(/.*)?)"))privateHeaders(reply);JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).as("Execution HTTP status; code %s",body.get("code").asString()).isEqualTo(status);shape(body,List.of("code","message","data","timestamp"));assertThat(body.get("code").asString()).isEqualTo(code);assertThat(body.get("message").isString()).isTrue();assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));return body.get("data");}
    private JsonNode business(HttpResponse<String> reply,String code,String reason){JsonNode error=company(reply,409,code);assertThat(error.get("reason").asString()).isEqualTo(reason);return error;}
    private JsonNode tom(HttpResponse<String> reply,int status){JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).isEqualTo(status);TomContract.assertSchema("OrderEnvelope",body);return body.get("data");}
    private Account account(String role,boolean temporary)throws Exception{String username="exec-test-"+key().substring(0,12),password=UpmsTestData.password();try(var connection=dataSource.getConnection()){return new Account(UpmsTestData.insert(connection,encoder,username,role,password,temporary,clock.instant()),username,password);}}
    private Browser browser(){var browser=new Browser();browsers.add(browser);return browser;}
    private Browser login(Account account)throws Exception{var browser=browser();company(browser.write("POST","/auth/login",fields("username",account.username(),"password",account.password()),null),200,"0000");return browser;}
    private void canonical(Connection connection)throws Exception{try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();}}
    private void awaitLock(String fragment)throws Exception{awaitLockCount(fragment,1);}
    private void awaitLockCount(String fragment,int minimum)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);try(var observer=dataSource.getConnection();var statement=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")){observer.setAutoCommit(true);statement.setQueryTimeout(2);statement.setString(1,"%"+fragment+"%");while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getLong(1)>=minimum)return;}Thread.sleep(20);}}throw new AssertionError("Expected PostgreSQL execution lock wait for "+fragment);}
    private void pause(String table,String event){jdbc.execute("CREATE FUNCTION logistics.test_execution_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202645,1); RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_execution_pause "+event+" ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_pause()");}
    private void unpause(String table){jdbc.execute("DROP TRIGGER test_execution_pause ON logistics."+table);jdbc.execute("DROP FUNCTION logistics.test_execution_pause()");}
    private void advisory(Connection connection,boolean acquire)throws Exception{try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute("SELECT pg_advisory_"+(acquire?"lock":"unlock")+"(202645,1)");}}
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
