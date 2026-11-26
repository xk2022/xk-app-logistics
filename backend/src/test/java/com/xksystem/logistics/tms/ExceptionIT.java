package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.fms.DriverQueries;
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
@Import(ExceptionIT.Configuration.class)
class ExceptionIT {
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
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    @Autowired TogglePolicy policy;
    @Autowired ToggleEvidencePolicy evidencePolicy;
    @Autowired ToggleStorage storage;
    @Autowired ToggleExceptionPolicy exceptionPolicy;
    @Autowired ExceptionQueries blockers;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired TaskQueries taskSources;
    private final JsonMapper json = JsonMapper.builder().build();
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
        clock.reset(); policy.mode = PolicyMode.SYNTHETIC_TEST_ONLY; evidencePolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;storage.reset();exceptionPolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;exceptionPolicy.ruleFault=null;
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



    @Test void reportRequestPhotoSubmissionAndResolutionKeepRawOriginalFactsAndIndependentVersions()throws Exception{
        Ready ready=ready("LOCAL");Map<String,String> sourcesBefore=dispatchAndSourceDigest();JsonNode report=report(ready,1,REPORT,key());
        exceptionRecord(report,ready,"OPEN",0);assertThat(report.get("description").asString()).isEqualTo(REPORT);
        Map<String,String> beforeRequiredSupplement=allDigest();
        business(admin.write("POST",managerPath(report)+"/resolve",fields("expectedVersion",0,"reason",RESOLVE),key()),"E1907","SUPPLEMENT_REQUIRED");
        assertThat(allDigest()).isEqualTo(beforeRequiredSupplement);
        JsonNode detail=company(driverBrowser.get(selfPath(ready,report)),200,"0000");detail(detail,report,false);
        JsonNode requested=request(report,0,REQUEST,key());exceptionRecord(requested,ready,"NEEDS_SUPPLEMENT",1);
        JsonNode active=requested.get("activeRequest");shape(active,List.of("id","reason","requestedAt","uploadedCount","evidenceIds"));
        assertThat(active.get("reason").asString()).isEqualTo(REQUEST);assertThat(active.get("uploadedCount").asInt()).isZero();assertThat(active.get("evidenceIds")).isEmpty();
        byte[] original=png(2,1,0xff113355);JsonNode uploaded=company(supplementUpload(driverBrowser,ready,report,id(active),original,"1","1",key(),null),200,"0000");
        shape(uploaded,List.of("exceptionId","assignmentId","taskId","version","requestId","evidence"));assertThat(uploaded.get("version").asLong()).isEqualTo(2);
        assertThat(uploaded.get("exceptionId").asString()).isEqualTo(id(report));assertThat(uploaded.get("requestId").asString()).isEqualTo(id(active));evidence(uploaded.get("evidence"),ready,"DEPARTED",1,original);
        JsonNode stillNeeds=company(driverBrowser.get(selfPath(ready,report)),200,"0000").get("exception");exceptionRecord(stillNeeds,ready,"NEEDS_SUPPLEMENT",2);
        assertThat(stillNeeds.get("activeRequest").get("uploadedCount").asInt()).isEqualTo(1);assertThat(stillNeeds.get("activeRequest").get("evidenceIds").get(0).asString()).isEqualTo(id(uploaded.get("evidence")));
        JsonNode pendingPhoto=company(admin.get(managerPath(report)+"/evidence"),200,"0000").get("content").get(0);shape(pendingPhoto,List.of("requestId","submitted","evidence"));assertThat(pendingPhoto.get("submitted").asBoolean()).isFalse();
        JsonNode submitted=submit(driverBrowser,ready,report,id(active),2,SUBMIT,List.of(id(uploaded.get("evidence"))),key());exceptionRecord(submitted,ready,"OPEN",3);
        assertThat(submitted.get("activeRequest").isNull()).isTrue();assertThat(company(admin.get(managerPath(report)+"/evidence"),200,"0000").get("content").get(0).get("submitted").asBoolean()).isTrue();
        JsonNode resolved=resolve(report,3,RESOLVE,key());exceptionRecord(resolved,ready,"RESOLVED",4);
        assertThat(resolved.get("description").asString()).isEqualTo(REPORT);assertThat(resolved.get("resolutionNote").asString()).isEqualTo(RESOLVE);assertThat(resolved.get("resolvedAt").isNull()).isFalse();
        Map<String,String> terminal=allDigest();
        business(supplementUpload(driverBrowser,ready,report,id(active),original,"4","1",key(),null),"E1902","INVALID_STATE");
        business(driverBrowser.write("POST",submitPath(ready,report,id(active)),fields("expectedVersion",4,"note",SUBMIT,"evidenceIds",List.of(id(uploaded.get("evidence")))),key()),"E1902","INVALID_STATE");
        business(admin.write("POST",managerPath(report)+"/resolve",fields("expectedVersion",4,"reason",RESOLVE),key()),"E1902","INVALID_STATE");
        assertThat(allDigest()).isEqualTo(terminal);
        List<String> expectedTypes=List.of("RESOLVED","SUPPLEMENT_SUBMITTED","SUPPLEMENT_EVIDENCE_UPLOADED","SUPPLEMENT_REQUESTED","REPORTED");
        JsonNode driverHistory=company(driverBrowser.get(selfPath(ready,report)+"/history?size=100"),200,"0000").get("content");assertThat(driverHistory).hasSize(5);
        JsonNode managerHistory=company(admin.get(managerPath(report)+"/history?size=100"),200,"0000").get("content");
        for(int index=0;index<5;index++){
            JsonNode event=driverHistory.get(index);shape(event,EVENT_FIELDS);assertThat(event.get("type").asString()).isEqualTo(expectedTypes.get(index));assertThat(event.get("toVersion").asLong()).isEqualTo(4-index);
            var fields=new ArrayList<>(EVENT_FIELDS);fields.add("actor");shape(managerHistory.get(index),fields);shape(managerHistory.get(index).get("actor"),List.of("role","displayName"));
        }
        assertThat(driverHistory.get(0).get("note").asString()).isEqualTo(RESOLVE);assertThat(driverHistory.get(1).get("note").asString()).isEqualTo(SUBMIT);
        assertThat(driverHistory.get(2).get("note").isNull()).isTrue();assertThat(driverHistory.get(2).get("evidenceIds")).hasSize(1);
        assertThat(driverHistory.get(3).get("note").asString()).isEqualTo(REQUEST);assertThat(driverHistory.get(4).get("note").asString()).isEqualTo(REPORT);
        assertThat(driverHistory.get(4).get("fromStatus").isNull()).isTrue();assertThat(driverHistory.get(4).get("fromVersion").isNull()).isTrue();
        assertPrivateContent(managerContent(admin,report,id(uploaded.get("evidence"))),original);
        assertThat(dispatchAndSourceDigest()).isEqualTo(sourcesBefore);assertThat(company(driverBrowser.get(path(ready)),200,"0000").get("execution")).isEqualTo(ready.departure().get("execution"));
    }

    @Test void allThirteenRoutesHaveFreshRoleBoundariesAndBoundedPagesWithoutIdentityOverrides()throws Exception{
        Cycle cycle=cycle();Ready ready=cycle.ready();JsonNode record=cycle.report();String requestId=cycle.requestId();Browser dispatcher=login(account("DISPATCHER",false));
        for(Browser manager:List.of(admin,dispatcher)){
            JsonNode page=company(manager.get("/tms/exceptions?assignmentId="+id(ready.assignment())+"&status=OPEN&size=1"),200,"0000");shape(page,List.of("content","page","size","totalElements","totalPages"));shape(page.get("content").get(0),SUMMARY_FIELDS);
            detail(company(manager.get(managerPath(record)),200,"0000"),cycle.submitted(),true);
            for(String self:List.of(selfBase(ready),selfPath(ready,record),selfPath(ready,record)+"/history"))company(manager.get(self),403,"E0403");
            company(manager.write("POST",selfBase(ready),fields("expectedExecutionVersion",1,"description",REPORT),key()),403,"E0403");
            company(manager.write("POST",selfPath(ready,record)+"/supplement-requests/"+requestId+"/submit",fields("expectedVersion",3,"note",SUBMIT,"evidenceIds",List.of(cycle.photoId())),key()),403,"E0403");
            company(supplementUpload(manager,ready,record,requestId,png(1,1,0xff223344),"3","1",key(),null),403,"E0403");
        }
        for(String managerRoute:List.of("/tms/exceptions",managerPath(record),managerPath(record)+"/history",managerPath(record)+"/evidence"))company(driverBrowser.get(managerRoute),403,"E0403");
        binaryError(managerContent(driverBrowser,record,cycle.photoId()),403,"E0403");
        company(driverBrowser.write("POST",managerPath(record)+"/supplement-requests",fields("expectedVersion",3,"reason",REQUEST),key()),403,"E0403");
        company(driverBrowser.write("POST",managerPath(record)+"/resolve",fields("expectedVersion",3,"reason",RESOLVE),key()),403,"E0403");
        for(String self:List.of(selfBase(ready),selfPath(ready,record),selfPath(ready,record)+"/history"))assertThat(company(otherBrowser.get(self),404,"E0404").isNull()).isTrue();
        for(String query:List.of("page=-1","page=100001","size=0","size=101","status=OTHER","sort=id","driverId="+id(driver),"page=0&page=1")){
            company(admin.get("/tms/exceptions?"+query),400,"E0400");company(driverBrowser.get(selfBase(ready)+"?"+query),400,"E0400");
        }
        assertThat(company(admin.get("/tms/exceptions?page=100000&size=1"),200,"0000").get("content")).isEmpty();
        for(String query:List.of("status=OPEN","page=0&page=1","size=101"))company(driverBrowser.get(selfPath(ready,record)+"/history?"+query),400,"E0400");
        company(admin.get(managerPath(record)+"?assignmentId="+id(ready.assignment())),400,"E0400");company(driverBrowser.get(selfPath(ready,record)+"?accountId="+driverAccount.id()),400,"E0400");
    }

    @Test void reportAndCommandTextAreRawUnicodeFactsAndHashIdentityIsNotTrimmedOrNfcNormalized()throws Exception{
        Ready ready=ready("LOCAL");String commandKey=key();JsonNode original=report(ready,1,REPORT,commandKey);Map<String,String> before=allDigest();
        String reordered="{\"description\":"+json.writeValueAsString(REPORT)+",\"expectedExecutionVersion\":1.0}";
        var replay=driverBrowser.send("POST",selfBase(ready),reordered,commandKey,driverBrowser.csrf());assertThat(company(replay,200,"0000")).isEqualTo(original);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        business(driverBrowser.write("POST",selfBase(ready),fields("expectedExecutionVersion",1,"description",java.text.Normalizer.normalize(REPORT,java.text.Normalizer.Form.NFC)),commandKey),"E1904","IDEMPOTENCY_KEY_REUSED");
        business(driverBrowser.write("POST",selfBase(ready),fields("expectedExecutionVersion",1,"description",REPORT.strip()),commandKey),"E1904","IDEMPOTENCY_KEY_REUSED");
        for(String blank:List.of(" ","\u00a0\u202f\ufeff"))company(driverBrowser.write("POST",selfBase(ready),fields("expectedExecutionVersion",1,"description",blank),key()),400,"E0400");
        assertThat(company(driverBrowser.get(selfPath(ready,original)),200,"0000").get("exception").get("description").asString()).isEqualTo(REPORT);assertThat(allDigest()).isEqualTo(before);
    }

    @Test void boundedRawJsonAndStrictShapeCannotSelectActorStateTimestampOrWaivers()throws Exception{
        Ready ready=ready("LOCAL");String token=driverBrowser.csrf();Map<String,String> before=allDigest();
        for(String raw:List.of("null","[]","{}","{\"expectedExecutionVersion\":1,\"description\":\"x\",\"severity\":\"CRITICAL\"}","{\"expectedExecutionVersion\":1,\"description\":\"x\",\"actorId\":\"x\"}","{\"expectedExecutionVersion\":1,\"description\":\"x\",\"blocking\":false}","{\"expectedExecutionVersion\":1,\"description\":\"x\",\"description\":\"y\"}","{\"expectedExecutionVersion\":1,\"description\":\"x\"} {}","{\"expectedExecutionVersion\":1.1,\"description\":\"x\"}","{\"expectedExecutionVersion\":1,\"description\":\"\\ud800\"}"))company(driverBrowser.send("POST",selfBase(ready),raw,key(),token),400,"E0400");
        String big="{\"expectedExecutionVersion\":1,\"description\":\""+"\\ud83d\\ude00".repeat(1500)+"\"}";
        for(boolean chunked:List.of(false,true)){
            byte[] bytes=big.getBytes(StandardCharsets.UTF_8);var request=driverBrowser.request("POST",selfBase(ready),bytes,"application/json",token).header("Idempotency-Key",key());if(chunked)request.POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(bytes)));
            assertThat(company(driverBrowser.client.send(request.build(),HttpResponse.BodyHandlers.ofString()),413,"E0413").isNull()).isTrue();
        }
        company(driverBrowser.write("POST",selfBase(ready)+"?taskId="+ready.assignment().get("taskId").asString(),fields("expectedExecutionVersion",1,"description",REPORT),key()),400,"E0400");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void driverReceiptsSurviveInactiveRequestAndLaterStatusButRemainCurrentPersonScoped()throws Exception{
        Cycle cycle=cycle();Map<String,String> before=allDigest();exceptionPolicy.mode=PolicyMode.UNCONFIRMED;storage.available=false;
        var uploadReplay=supplementUpload(driverBrowser,cycle.ready(),cycle.report(),cycle.requestId(),cycle.bytes(),"1","1",cycle.uploadKey(),null);
        assertThat(company(uploadReplay,200,"0000")).isEqualTo(cycle.upload());assertThat(uploadReplay.headers().firstValue("Idempotency-Replayed")).contains("true");
        var submitReplay=driverBrowser.write("POST",submitPath(cycle.ready(),cycle.report(),cycle.requestId()),fields("expectedVersion",2,"note",SUBMIT,"evidenceIds",List.of(cycle.photoId())),cycle.submitKey());
        assertThat(company(submitReplay,200,"0000")).isEqualTo(cycle.submitted());assertThat(submitReplay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(allDigest()).isEqualTo(before);
        exceptionPolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;storage.available=true;
        JsonNode next=request(cycle.report(),3," 第二次補件\u0000 ",key());assertThat(next.get("activeRequest").get("id").asString()).isNotEqualTo(cycle.requestId());before=allDigest();
        assertThat(company(driverBrowser.write("POST",submitPath(cycle.ready(),cycle.report(),cycle.requestId()),fields("expectedVersion",2,"note",SUBMIT,"evidenceIds",List.of(cycle.photoId())),cycle.submitKey()),200,"0000")).isEqualTo(cycle.submitted());
        business(driverBrowser.write("POST",submitPath(cycle.ready(),cycle.report(),cycle.requestId()),fields("expectedVersion",4,"note",SUBMIT,"evidenceIds",List.of(cycle.photoId())),key()),"E1902","INVALID_STATE");
        assertThat(allDigest()).isEqualTo(before);
        company(admin.write("PATCH","/fms/drivers/"+id(driver),fields("version",0,"accountId",null),null),200,"0000");
        assertThat(company(supplementUpload(driverBrowser,cycle.ready(),cycle.report(),cycle.requestId(),cycle.bytes(),"1","1",cycle.uploadKey(),null),404,"E0404").isNull()).isTrue();
    }

    @Test void managerHistoryAndSuccessfulReceiptsRemainAuthorizedAfterCurrentDriverSourceAndOccupationsAreGone()throws Exception{
        Cycle cycle=cycle();Ready ready=cycle.ready();JsonNode report=cycle.report();
        company(admin.write("PATCH","/fms/drivers/"+id(driver),fields("version",0,"accountId",null),null),200,"0000");
        jdbc.update("UPDATE logistics.order_core SET lifecycle_status='CLOSED' WHERE id=?",UUID.fromString(ready.assignment().get("orderId").asString()));
        jdbc.update("UPDATE logistics.tms_resource_reservation SET released_at=CURRENT_TIMESTAMP,release_reason='合成後續歷史資格' WHERE assignment_id=?",uuid(ready.assignment()));
        Map<String,String> before=allDigest();
        detail(company(admin.get(managerPath(report)),200,"0000"),cycle.submitted(),true);
        assertThat(company(admin.get(managerPath(report)+"/history"),200,"0000").get("content")).hasSize(4);
        assertThat(company(admin.get(managerPath(report)+"/evidence"),200,"0000").get("content")).hasSize(1);assertPrivateContent(managerContent(admin,report,cycle.photoId()),cycle.bytes());
        var replay=admin.write("POST",managerPath(report)+"/supplement-requests",fields("expectedVersion",0,"reason",REQUEST),cycle.requestKey());
        assertThat(company(replay,200,"0000")).isEqualTo(cycle.requested());assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        business(admin.write("POST",managerPath(report)+"/supplement-requests",fields("expectedVersion",3,"reason",REQUEST),key()),"E1902","INVALID_STATE");
        assertThat(company(driverBrowser.get(selfPath(ready,report)),404,"E0404").isNull()).isTrue();assertThat(allDigest()).isEqualTo(before);
    }

    @Test void uploadTokensExposeOnlyTheCorrectVersionUnitAndUploadIsNotSubmission()throws Exception{
        Ready ready=ready("LOCAL");JsonNode report=report(ready,1,REPORT,key());JsonNode requested=request(report,0,REQUEST,key());String requestId=id(requested.get("activeRequest"));Map<String,String> before=allDigest();
        JsonNode staleReport=business(driverBrowser.write("POST",selfBase(ready),fields("expectedExecutionVersion",0,"description",REPORT),key()),"E1901","VERSION_CONFLICT");shape(staleReport,List.of("reason","actualExecutionVersion"));assertThat(staleReport.get("actualExecutionVersion").asLong()).isEqualTo(1);
        JsonNode reviewVersion=business(supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff112233),"0","0",key(),null),"E1901","VERSION_CONFLICT");shape(reviewVersion,List.of("reason","actualVersion"));assertThat(reviewVersion.get("actualVersion").asLong()).isEqualTo(1);
        JsonNode executionVersion=business(supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff112233),"1","0",key(),null),"E1901","VERSION_CONFLICT");shape(executionVersion,List.of("reason","actualExecutionVersion"));assertThat(executionVersion.get("actualExecutionVersion").asLong()).isEqualTo(1);
        business(driverBrowser.write("POST",submitPath(ready,report,requestId),fields("expectedVersion",1,"note",SUBMIT,"evidenceIds",List.of()),key()),"E1907","SUPPLEMENT_REQUIRED");assertThat(allDigest()).isEqualTo(before);
        JsonNode uploaded=company(supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff112233),"1","1",key(),null),200,"0000");
        business(admin.write("POST",managerPath(report)+"/resolve",fields("expectedVersion",2,"reason",RESOLVE),key()),"E1902","INVALID_STATE");
        JsonNode current=company(admin.get(managerPath(report)),200,"0000").get("exception");exceptionRecord(current,ready,"NEEDS_SUPPLEMENT",2);assertThat(current.get("activeRequest").get("evidenceIds").get(0).asString()).isEqualTo(id(uploaded.get("evidence")));
    }

    @Test void newRequestNeedsNewRequestLinkedEvidenceAndSubmissionUsesItsOwnProofAfterExecutionAdvances()throws Exception{
        Ready ready=ready("LOCAL");JsonNode report=report(ready,1,REPORT,key());JsonNode requested=request(report,0,REQUEST,key());String requestId=id(requested.get("activeRequest"));
        JsonNode uploaded=company(supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff112233),"1","1",key(),null),200,"0000");String photo=id(uploaded.get("evidence"));
        // An unchanged M402 rule can independently accept the same verified same-stage photo.
        company(progress(ready,1,"IN_PROGRESS",List.of(photo),key()),200,"0000");
        JsonNode submitted=submit(driverBrowser,ready,report,requestId,2,SUBMIT,List.of(photo),key());exceptionRecord(submitted,ready,"OPEN",3);
        JsonNode next=request(report,3," 新請求 ",key());String nextId=id(next.get("activeRequest"));Map<String,String> before=allDigest();
        business(driverBrowser.write("POST",submitPath(ready,report,nextId),fields("expectedVersion",4,"note",SUBMIT,"evidenceIds",List.of(photo)),key()),"E1908","EVIDENCE_INVALID");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void supplementAtAwaitingCompletionUsesIndependentRuleWithoutWideningOriginalPhotoUpload()throws Exception{
        Ready ready=ready("LOCAL");JsonNode first=company(upload(ready,png(1,1,0xff112233),"1",key()),200,"0000");company(progress(ready,1,"IN_PROGRESS",List.of(id(first)),key()),200,"0000");
        JsonNode second=company(upload(ready,png(1,1,0xff223344),"2",key()),200,"0000");company(progress(ready,2,"AWAITING_COMPLETION",List.of(id(second)),key()),200,"0000");
        JsonNode report=report(ready,3,REPORT,key());JsonNode requested=request(report,0,REQUEST,key());
        business(upload(ready,png(1,1,0xff334455),"3",key()),"E1802","INVALID_STATE");
        JsonNode uploaded=company(supplementUpload(driverBrowser,ready,report,id(requested.get("activeRequest")),png(1,1,0xff334455),"1","3",key(),null),200,"0000");
        assertThat(uploaded.get("evidence").get("phase").asString()).isEqualTo("AWAITING_COMPLETION");assertThat(uploaded.get("evidence").get("executionVersion").asLong()).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings={"REQUEST","RESOLVE"})
    void requestAndResolutionCompeteOnlyFromLegitimateOpenFulfilledPrestate(String winner)throws Exception{
        Cycle cycle=cycle();String token=admin.csrf();pause("tms_exception","BEFORE UPDATE");
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()){
            advisory(held,true);String first=winner.equals("REQUEST")?"supplement-requests":"resolve",second=winner.equals("REQUEST")?"resolve":"supplement-requests";
            var winning=pool.submit(()->admin.send("POST",managerPath(cycle.report())+"/"+first,json.writeValueAsString(fields("expectedVersion",3,"reason",winner.equals("REQUEST")?REQUEST:RESOLVE)),key(),token));
            try{awaitLock("tms_exception");var losing=pool.submit(()->admin.send("POST",managerPath(cycle.report())+"/"+second,json.writeValueAsString(fields("expectedVersion",3,"reason",winner.equals("REQUEST")?RESOLVE:REQUEST)),key(),token));awaitLock("upms_lock");advisory(held,false);company(winning.get(20,TimeUnit.SECONDS),200,"0000");business(losing.get(20,TimeUnit.SECONDS),"E1901","VERSION_CONFLICT");}finally{advisory(held,false);}
        }finally{unpause("tms_exception");}
        exceptionRecord(company(admin.get(managerPath(cycle.report())),200,"0000").get("exception"),cycle.ready(),winner.equals("REQUEST")?"NEEDS_SUPPLEMENT":"RESOLVED",4);
    }

    @ParameterizedTest @ValueSource(strings={"UPLOAD","SUBMIT"})
    void uploadAndSubmissionCompeteOnlyWhenBothHaveAValidNeedsSupplementPrestate(String winner)throws Exception{
        Ready ready=ready("LOCAL");JsonNode report=report(ready,1,REPORT,key());String requestId=id(request(report,0,REQUEST,key()).get("activeRequest"));
        JsonNode photo=company(supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff112233),"1","1",key(),null),200,"0000");String token=driverBrowser.csrf();
        String table=winner.equals("UPLOAD")?"tms_execution_evidence_object":"tms_exception";pause(table,winner.equals("UPLOAD")?"BEFORE INSERT":"BEFORE UPDATE");
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()){
            advisory(held,true);var first=pool.submit(()->winner.equals("UPLOAD")?supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff334455),"2","1",key(),token):driverBrowser.send("POST",submitPath(ready,report,requestId),json.writeValueAsString(fields("expectedVersion",2,"note",SUBMIT,"evidenceIds",List.of(id(photo.get("evidence"))))),key(),token));
            try{awaitLock(table);var second=pool.submit(()->winner.equals("UPLOAD")?driverBrowser.send("POST",submitPath(ready,report,requestId),json.writeValueAsString(fields("expectedVersion",2,"note",SUBMIT,"evidenceIds",List.of(id(photo.get("evidence"))))),key(),token):supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff334455),"2","1",key(),token));awaitLock("upms_lock");advisory(held,false);company(first.get(20,TimeUnit.SECONDS),200,"0000");business(second.get(20,TimeUnit.SECONDS),"E1901","VERSION_CONFLICT");}finally{advisory(held,false);}
        }finally{unpause(table);}
        exceptionRecord(company(admin.get(managerPath(report)),200,"0000").get("exception"),ready,winner.equals("UPLOAD")?"NEEDS_SUPPLEMENT":"OPEN",3);
    }


    @ParameterizedTest @ValueSource(strings={"REPORT:tms_exception","REPORT:tms_exception_event","REPORT:tms_exception_command","REQUEST:tms_exception_supplement_request","UPLOAD:tms_execution_evidence_object","UPLOAD:tms_execution_evidence","UPLOAD:tms_exception","UPLOAD:tms_exception_event","UPLOAD:tms_exception_supplement_evidence","UPLOAD:tms_exception_command","SUBMIT:tms_exception","SUBMIT:tms_exception_event","SUBMIT:tms_exception_event_evidence","SUBMIT:tms_exception_command","RESOLVE:tms_exception","RESOLVE:tms_exception_event","RESOLVE:tms_exception_command"})
    void eachPersistenceFailureRollsBackWholeExceptionCommandIncludingPrivateObjectsAndOriginalReceipt(String specification)throws Exception{
        String[] parts=specification.split(":");Step step=step(parts[0]);String table=parts[1];Map<String,String> before=allDigest();
        failAt(table,table.equals("tms_exception")&&!parts[0].equals("REPORT")?"BEFORE UPDATE":"BEFORE INSERT");
        try{assertThat(company(execute(step),500,"E0500").isNull()).isTrue();}finally{dropFailure(table);}
        assertThat(allDigest()).isEqualTo(before);var retry=execute(step);company(retry,200,"0000");assertThat(retry.headers().firstValue("Idempotency-Replayed")).contains("false");
    }

    @ParameterizedTest @ValueSource(strings={"REPORT:tms_exception","REPORT:tms_exception_event","REPORT:tms_exception_command","UPLOAD:tms_execution_evidence"})
    void deferredActorForeignKeyFailureUsesExceptionNamespaceAndRollsBackEveryOwnedFact(String specification)throws Exception{
        String[] parts=specification.split(":");Step step=step(parts[0]);String table=parts[1];Map<String,String> before=allDigest();
        jdbc.execute("CREATE FUNCTION logistics.test_execution_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN SET CONSTRAINTS ALL DEFERRED; NEW.actor_id='00000000-0000-4000-8000-000000009999'; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_execution_fail BEFORE INSERT ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_fail()");
        try{business(execute(step),"E1906","PROTECTED_RELATION");}finally{dropFailure(table);}assertThat(allDigest()).isEqualTo(before);
    }

    @Test void reportsRequestsSubmissionLinksHistoryAndReceiptsCannotOverwriteOrDeleteOriginalFacts()throws Exception{
        Cycle cycle=cycle();Map<String,String> before=allDigest();
        assertThat(jdbc.queryForObject("SELECT encode(description,'hex') FROM logistics.tms_exception WHERE id=?",String.class,uuid(cycle.report())))
                .isEqualTo(java.util.HexFormat.of().formatHex(REPORT.getBytes(StandardCharsets.UTF_8)));
        for(String table:EXCEPTION_TABLES){assertThatThrownBy(()->jdbc.update("UPDATE logistics."+table+" SET id=id")).isInstanceOf(DataAccessException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM logistics."+table)).isInstanceOf(DataAccessException.class);}
        assertThat(allDigest()).isEqualTo(before);
        JsonNode oldActor=company(admin.get(managerPath(cycle.report())+"/history"),200,"0000").get("content").get(0).get("actor");
        jdbc.update("UPDATE logistics.upms_account SET display_name='後來更改的合成顯示名稱' WHERE id=?",driverAccount.id());
        assertThat(company(admin.get(managerPath(cycle.report())+"/history"),200,"0000").get("content").get(0).get("actor")).isEqualTo(oldActor);
    }

    @Test void boundedActiveRequestReloadSetStopsNewArtifactsAtThirtyTwoWithoutBlockingHistoricalReceipt()throws Exception{
        Ready ready=ready("LOCAL");JsonNode report=report(ready,1,REPORT,key()),requested=request(report,0,REQUEST,key());String requestId=id(requested.get("activeRequest")),firstKey=key();byte[] bytes=png(1,1,0xff224466);JsonNode first=null;
        for(int index=0;index<32;index++){
            JsonNode result=company(supplementUpload(driverBrowser,ready,report,requestId,bytes,Integer.toString(index+1),"1",index==0?firstKey:key(),null),200,"0000");if(index==0)first=result;
        }
        JsonNode current=company(driverBrowser.get(selfPath(ready,report)),200,"0000").get("exception");assertThat(current.get("version").asLong()).isEqualTo(33);assertThat(current.get("activeRequest").get("uploadedCount").asInt()).isEqualTo(32);assertThat(current.get("activeRequest").get("evidenceIds")).hasSize(32);
        Map<String,String> before=allDigest();business(supplementUpload(driverBrowser,ready,report,requestId,bytes,"33","1",key(),null),"E1902","INVALID_STATE");
        var replay=supplementUpload(driverBrowser,ready,report,requestId,bytes,"1","1",firstKey,null);assertThat(company(replay,200,"0000")).isEqualTo(first);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(allDigest()).isEqualTo(before);
    }

    @Test void reportSameKeyTryLockIsBoundedBeforeCanonicalQueueAndOnlyOneOriginalReportCommits()throws Exception{
        Ready ready=ready("LOCAL");Browser another=login(driverAccount);String firstToken=driverBrowser.csrf(),secondToken=another.csrf(),commandKey=key(),body=json.writeValueAsString(fields("expectedExecutionVersion",1,"description",REPORT));pause("tms_exception","BEFORE INSERT");
        try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
            advisory(held,true);var first=pool.submit(()->driverBrowser.send("POST",selfBase(ready),body,commandKey,firstToken));
            try{awaitLock("tms_exception");long start=System.nanoTime();business(another.send("POST",selfBase(ready),body,commandKey,secondToken),"E1905","IDEMPOTENCY_IN_PROGRESS");assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(15));}finally{advisory(held,false);}company(first.get(20,TimeUnit.SECONDS),200,"0000");
        }finally{unpause("tms_exception");}assertThat(count("tms_exception")).isEqualTo(1);assertThat(count("tms_exception_event")).isEqualTo(1);assertThat(count("tms_exception_command")).isEqualTo(1);
    }

    @Test void blockingPortRequiresWritableTransactionLocksEvenZeroCaseRootAndCountsOnlyUnresolvedFacts()throws Exception{
        Ready ready=ready("LOCAL");UUID assignment=uuid(ready.assignment());
        assertThatThrownBy(()->blockers.lockBlockingForAssignment(assignment)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var readOnly=new org.springframework.transaction.support.TransactionTemplate(transactionManager);readOnly.setReadOnly(true);
        assertThatThrownBy(()->readOnly.execute(status->blockers.lockBlockingForAssignment(assignment))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
            var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            var zero=tx.execute(status->{jdbc.queryForObject("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE",Integer.class);jdbc.queryForObject("SELECT id FROM logistics.fms_driver WHERE id=? FOR UPDATE",UUID.class,uuid(driver));taskSources.lockCurrentForDispatch(UUID.fromString(ready.assignment().get("taskId").asString()));var result=blockers.lockBlockingForAssignment(assignment);
                pending.set(pool.submit(()->{try(var connection=dataSource.getConnection();var statement=connection.prepareStatement("SELECT id FROM logistics.tms_assignment WHERE id=? FOR UPDATE")){connection.setAutoCommit(false);statement.setQueryTimeout(20);statement.setObject(1,assignment);try(var row=statement.executeQuery()){boolean exists=row.next();connection.rollback();return exists;}}}));
                try{awaitLock("tms_assignment");}catch(Exception failure){throw new IllegalStateException(failure);}assertThat(pending.get().isDone()).isFalse();return result;});
            assertThat(zero.assignmentId()).isEqualTo(assignment);assertThat(zero.unresolvedCount()).isZero();assertThat(pending.get().get(20,TimeUnit.SECONDS)).isTrue();
        }
        JsonNode report=report(ready,1,REPORT,key());assertThat(blocking(ready).unresolvedCount()).isEqualTo(1);
        String req=id(request(report,0,REQUEST,key()).get("activeRequest"));JsonNode photo=company(supplementUpload(driverBrowser,ready,report,req,png(1,1,0xff112233),"1","1",key(),null),200,"0000");submit(driverBrowser,ready,report,req,2,SUBMIT,List.of(id(photo.get("evidence"))),key());resolve(report,3,RESOLVE,key());assertThat(blocking(ready).unresolvedCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"DRIVER_REPORT","DRIVER_UPLOAD_REPLAY","DRIVER_SUBMIT_REPLAY","MANAGER_REQUEST","MANAGER_HISTORY","MANAGER_CONTENT"})
    void logoutAheadOfQueuedExceptionActionOrHistoryInvalidatesItsCapturedOriginalSession(String action)throws Exception{
        Cycle cycle=cycle();Browser browser=action.startsWith("DRIVER")?driverBrowser:admin;String token=browser.csrf();Map<String,String> before=allDigest();List<HttpResponse<byte[]>> responses;
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()){
            held.setAutoCommit(false);canonical(held);var logout=pool.submit(()->browser.client.send(browser.request("POST","/auth/logout","{}".getBytes(StandardCharsets.UTF_8),"application/json",token).build(),HttpResponse.BodyHandlers.ofByteArray()));
            try{awaitLockCount("upms_lock",1);var pending=pool.submit(()->queuedException(browser,cycle,action,token));try{awaitLockCount("upms_lock",2);}finally{held.rollback();}responses=List.of(logout.get(20,TimeUnit.SECONDS),pending.get(20,TimeUnit.SECONDS));}finally{held.rollback();}
        }
        binaryError(responses.get(0),200,"0000");assertThat(binaryError(responses.get(1),401,"E0401").isNull()).isTrue();assertThat(responses.get(1).headers().firstValue("Idempotency-Replayed")).isEmpty();assertThat(allDigest()).isEqualTo(before);
    }

    @Test void supplementUploadKeepsDualHeaderGrammarRawStreamCapAndHeaderOnlyCsrfOnItsExactNestedRoute()throws Exception{
        Ready ready=ready("LOCAL");JsonNode record=report(ready,1,REPORT,key());String requestId=id(request(record,0,REQUEST,key()).get("activeRequest"));String token=driverBrowser.csrf();Map<String,String> before=allDigest();byte[] valid=png(1,1,0xff335577);
        for(String invalid:List.of("01","+1","-1","1.0","1e0","1,1","9007199254740992")){
            var bad=supplementRequest(driverBrowser,ready,record,requestId,valid,invalid,"1",key(),token,"image/png").build();company(driverBrowser.client.send(bad,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        }
        for(String header:List.of("X-Exception-Version","X-Execution-Version")){
            var duplicate=supplementRequest(driverBrowser,ready,record,requestId,valid,"1","1",key(),token,"image/png").header(header,"1").build();company(driverBrowser.client.send(duplicate,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        }
        var missing=supplementRequest(driverBrowser,ready,record,requestId,valid,null,"1",key(),token,"image/png").build();company(driverBrowser.client.send(missing,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        Browser anonymous=browser();
        for(String media:List.of("multipart/form-data; boundary=synthetic-exception","application/x-www-form-urlencoded")){
            byte[] body="--synthetic-exception\r\nnot-a-form".getBytes(StandardCharsets.US_ASCII);
            var withCsrf=supplementRequest(driverBrowser,ready,record,requestId,body,"1","1",key(),token,media).build();company(driverBrowser.client.send(withCsrf,HttpResponse.BodyHandlers.ofString()),415,"E0415");
            var noCsrf=supplementRequest(driverBrowser,ready,record,requestId,body,"1","1",key(),null,media).build();company(driverBrowser.client.send(noCsrf,HttpResponse.BodyHandlers.ofString()),403,"E0403");
            var noAuth=supplementRequest(anonymous,ready,record,requestId,body,"1","1",key(),null,media).build();company(anonymous.client.send(noAuth,HttpResponse.BodyHandlers.ofString()),401,"E0401");
        }
        byte[] overflow=new byte[1048577];var chunked=supplementRequest(driverBrowser,ready,record,requestId,overflow,"1","1",key(),token,"image/png").POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(overflow))).build();assertThat(company(driverBrowser.client.send(chunked,HttpResponse.BodyHandlers.ofString()),413,"E0413").isNull()).isTrue();
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void selectedSupplementIdsAreCanonicalSetsWhileRawSubmissionNoteRemainsExactHashIdentity()throws Exception{
        Ready ready=ready("LOCAL");JsonNode report=report(ready,1,REPORT,key());String requestId=id(request(report,0,REQUEST,key()).get("activeRequest"));
        JsonNode one=company(supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff111111),"1","1",key(),null),200,"0000"),two=company(supplementUpload(driverBrowser,ready,report,requestId,png(1,1,0xff222222),"2","1",key(),null),200,"0000");
        String a=id(one.get("evidence")),b=id(two.get("evidence")),commandKey=key();String path=submitPath(ready,report,requestId);
        var body=fields("expectedVersion",new java.math.BigDecimal("3.00"),"note",SUBMIT,"evidenceIds",List.of(b.toUpperCase(java.util.Locale.ROOT),a));JsonNode original=company(driverBrowser.write("POST",path,body,commandKey),200,"0000");Map<String,String> before=allDigest();
        var replay=driverBrowser.write("POST",path,fields("expectedVersion",3,"note",SUBMIT,"evidenceIds",List.of(a.toUpperCase(java.util.Locale.ROOT),b)),commandKey);assertThat(company(replay,200,"0000")).isEqualTo(original);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        business(driverBrowser.write("POST",path,fields("expectedVersion",3,"note",java.text.Normalizer.normalize(SUBMIT,java.text.Normalizer.Form.NFC),"evidenceIds",List.of(a,b)),commandKey),"E1904","IDEMPOTENCY_KEY_REUSED");
        company(driverBrowser.write("POST",path,fields("expectedVersion",3,"note",SUBMIT,"evidenceIds",List.of(a,a.toUpperCase(java.util.Locale.ROOT))),key()),400,"E0400");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void managerPrivateProtocolAuthorizesOnlyExceptionLinkedPhotosAndSeparatesReadPolicyFromMetadataFacts()throws Exception{
        Cycle cycle=cycle();JsonNode unrelated=company(upload(cycle.ready(),png(1,1,0xffaabb11),"1",key()),200,"0000");Map<String,String> before=allDigest();
        assertThat(company(admin.get(managerPath(cycle.report())+"/evidence"),200,"0000").get("content")).hasSize(1);
        binaryError(managerContent(admin,cycle.report(),id(unrelated)),404,"E0404");
        String photoPath=managerPath(cycle.report())+"/evidence/"+cycle.photoId()+"/content";
        var conditional=admin.request("GET",photoPath,null,null,null).setHeader("Accept","image/png").header("If-None-Match","*").header("If-Modified-Since","Tue, 01 Jan 2030 00:00:00 GMT").build();assertPrivateContent(admin.client.send(conditional,HttpResponse.BodyHandlers.ofByteArray()),cycle.bytes());
        var ranged=admin.request("GET",photoPath,null,null,null).setHeader("Accept","image/png").header("Range","bytes=0-1").build();binaryError(admin.client.send(ranged,HttpResponse.BodyHandlers.ofByteArray()),400,"E0400");
        var head=admin.request("HEAD",photoPath,null,null,null).setHeader("Accept","image/png").build();assertThat(admin.client.send(head,HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(405);
        exceptionPolicy.mode=PolicyMode.UNCONFIRMED;binaryBusiness(managerContent(admin,cycle.report(),cycle.photoId()),"E1903","POLICY_NOT_CONFIRMED");
        assertThat(company(admin.get(managerPath(cycle.report())+"/evidence"),200,"0000").get("content")).hasSize(1);assertThat(allDigest()).isEqualTo(before);
    }

    @Test void samePhysicalDriverRebindingAllowsNewActorSubmissionWithoutRewritingOriginalUploaderAudit()throws Exception{
        Ready ready=ready("LOCAL");JsonNode report=report(ready,1,REPORT,key());String requestId=id(request(report,0,REQUEST,key()).get("activeRequest")),uploadKey=key();byte[] bytes=png(1,1,0xff447799);
        JsonNode photo=company(supplementUpload(driverBrowser,ready,report,requestId,bytes,"1","1",uploadKey,null),200,"0000");
        Account replacement=account("DRIVER",false);Browser replacementBrowser=login(replacement);
        company(admin.write("PATCH","/fms/drivers/"+id(driver),fields("version",0,"accountId",replacement.id().toString()),null),200,"0000");
        company(driverBrowser.get(selfPath(ready,report)),404,"E0404");assertThat(company(supplementUpload(driverBrowser,ready,report,requestId,bytes,"1","1",uploadKey,null),404,"E0404").isNull()).isTrue();
        exceptionRecord(company(replacementBrowser.get(selfPath(ready,report)),200,"0000").get("exception"),ready,"NEEDS_SUPPLEMENT",2);
        JsonNode submitted=submit(replacementBrowser,ready,report,requestId,2,SUBMIT,List.of(id(photo.get("evidence"))),key());exceptionRecord(submitted,ready,"OPEN",3);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM logistics.tms_execution_evidence WHERE id=?",UUID.class,uuid(photo.get("evidence")))).isEqualTo(driverAccount.id());
        assertThat(jdbc.queryForObject("SELECT actor_id FROM logistics.tms_exception_event WHERE exception_id=? AND type='SUPPLEMENT_SUBMITTED'",UUID.class,uuid(report))).isEqualTo(replacement.id());
    }

    @Test void normalSessionAndFirstPasswordBarriersApplyBeforeManagementOrDriverHistoricalReceipts()throws Exception{
        Cycle cycle=cycle();String token=admin.csrf();Map<String,String> before=allDigest();
        jdbc.update("UPDATE logistics.upms_account SET enabled=false,auth_version=auth_version+1 WHERE id=?",administrator.id());
        company(admin.send("POST",managerPath(cycle.report())+"/supplement-requests",json.writeValueAsString(fields("expectedVersion",0,"reason",REQUEST)),cycle.requestKey(),token),401,"E0401");
        company(admin.get(managerPath(cycle.report())+"/history"),401,"E0401");
        Browser temporaryManager=login(account("ADMIN",true)),temporaryDriver=login(account("DRIVER",true));
        company(temporaryManager.get(managerPath(cycle.report())),403,"E0403");
        company(temporaryDriver.write("POST",selfBase(cycle.ready()),fields("expectedExecutionVersion",1,"description",REPORT),key()),403,"E0403");
        assertThat(allDigest()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"EMPTY","INVALID"})
    void missingOrInvalidSupplementRuleKeepsFactsReadableButCannotAdvertiseOrExecuteManagementRequest(String ruleFault)throws Exception{
        Ready ready=ready("LOCAL");JsonNode report=report(ready,1,REPORT,key());exceptionPolicy.ruleFault=ruleFault;Map<String,String> before=allDigest();
        JsonNode manager=company(admin.get(managerPath(report)),200,"0000");assertThat(manager.get("exception")).isEqualTo(report);
        JsonNode availability=manager.get("actions").get("requestSupplement");assertThat(availability.get("allowed").asBoolean()).isFalse();assertThat(availability.get("blocker").asString()).isEqualTo("POLICY_NOT_CONFIRMED");assertThat(manager.get("actions").get("requirements").isNull()).isTrue();
        assertThat(company(driverBrowser.get(selfPath(ready,report)),200,"0000").get("exception")).isEqualTo(report);
        business(admin.write("POST",managerPath(report)+"/supplement-requests",fields("expectedVersion",0,"reason",REQUEST),key()),"E1903","POLICY_NOT_CONFIRMED");assertThat(allDigest()).isEqualTo(before);
    }

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
    private Map<String,String> allDigest(){var result=dispatchAndSourceDigest();for(String table:EXCEPTION_TABLES)result.put(table,digest(table,"id"));for(String table:EVIDENCE_TABLES)result.put(table,digest(table,table.equals("tms_execution_event_evidence")?"event_id,evidence_id":"id"));for(String table:EXECUTION_TABLES)result.put(table,digest(table,table.equals("tms_execution")?"assignment_id":"id"));return result;}
    private Map<String,String> dispatchAndSourceDigest(){var result=new LinkedHashMap<String,String>();for(String table:DISPATCH_TABLES)result.put(table,digest(table,table.equals("tms_task_dispatch")?"task_id":"id"));for(String table:List.of("fms_driver","fms_vehicle","order_core","tom_order","cargo_item","transport_task","task_dependency","order_draft_revision","api_idempotency","order_acceptance_event","crm_customer","crm_contact","crm_address"))result.put(table,digest(table,table.equals("tom_order")?"order_id":table.equals("task_dependency")?"task_id,predecessor_task_id":"id"));return result;}
    private String digest(String table,String ordering){return jdbc.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+ordering+")::text,'[]')) FROM logistics."+table+" t",String.class);}
    private void shape(JsonNode value,List<String> expected){assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);}
    private JsonNode company(HttpResponse<String> reply,int status,String code){if(reply.uri().getPath().matches("/api/v1/tms/my-tasks/[^/]+/(progress-policy|progress|evidence|exceptions)(/.*)?")||reply.uri().getPath().startsWith("/api/v1/tms/exceptions"))privateHeaders(reply);JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).as("Execution HTTP status; code %s",body.get("code").asString()).isEqualTo(status);shape(body,List.of("code","message","data","timestamp"));assertThat(body.get("code").asString()).isEqualTo(code);assertThat(body.get("message").isString()).isTrue();assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));return body.get("data");}
    private JsonNode business(HttpResponse<String> reply,String code,String reason){JsonNode error=company(reply,409,code);assertThat(error.get("reason").asString()).isEqualTo(reason);return error;}
    private JsonNode tom(HttpResponse<String> reply,int status){JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).isEqualTo(status);TomContract.assertSchema("OrderEnvelope",body);return body.get("data");}
    private Account account(String role,boolean temporary)throws Exception{String username="exec-test-"+key().substring(0,12),password=UpmsTestData.password();try(var connection=dataSource.getConnection()){return new Account(UpmsTestData.insert(connection,encoder,username,role,password,temporary,clock.instant()),username,password);}}
    private Browser browser(){var browser=new Browser();browsers.add(browser);return browser;}
    private Browser login(Account account)throws Exception{var browser=browser();company(browser.write("POST","/auth/login",fields("username",account.username(),"password",account.password()),null),200,"0000");return browser;}
    private void canonical(Connection connection)throws Exception{try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();}}
    private void awaitLock(String fragment)throws Exception{awaitLockCount(fragment,1);}
    private void awaitLockCount(String fragment,int minimum)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);try(var observer=dataSource.getConnection();var statement=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")){observer.setAutoCommit(true);statement.setQueryTimeout(2);statement.setString(1,"%"+fragment+"%");while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getLong(1)>=minimum)return;}Thread.sleep(20);}}throw new AssertionError("Expected PostgreSQL execution lock wait for "+fragment);}
    private void pause(String table,String event){jdbc.execute("CREATE FUNCTION logistics.test_execution_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202643,1); RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_execution_pause "+event+" ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_pause()");}
    private void unpause(String table){jdbc.execute("DROP TRIGGER test_execution_pause ON logistics."+table);jdbc.execute("DROP FUNCTION logistics.test_execution_pause()");}
    private void advisory(Connection connection,boolean acquire)throws Exception{try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute("SELECT pg_advisory_"+(acquire?"lock":"unlock")+"(202643,1)");}}
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
