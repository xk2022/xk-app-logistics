package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.fms.DriverQueries;
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
@Import(EvidenceIT.Configuration.class)
class EvidenceIT {
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
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    @Autowired TogglePolicy policy;
    @Autowired ToggleEvidencePolicy evidencePolicy;
    @Autowired ToggleStorage storage;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Browser> browsers = new ArrayList<>();
    private Account administrator, driverAccount, otherAccount;
    private Browser admin, driverBrowser, otherBrowser;
    private JsonNode driver, otherDriver, main, otherMain;

    @BeforeEach void isolatedRealSourcesAndNormalCurrentDriverBindings() throws Exception {
        jdbc.execute("TRUNCATE TABLE logistics.tom_order_closure_command,logistics.tom_order_closure_task,logistics.tom_order_closure,logistics.tms_completion_confirmation_command,logistics.tms_completion_confirmation,logistics.tms_completion_command,logistics.tms_completion,logistics.tms_exception_event_evidence,logistics.tms_exception_supplement_evidence,logistics.tms_exception_command,logistics.tms_exception_event,logistics.tms_exception_supplement_request,logistics.tms_exception,logistics.tms_execution_evidence_command,logistics.tms_execution_event_evidence,logistics.tms_execution_evidence_object,logistics.tms_execution_evidence,logistics.tms_execution_command,logistics.tms_execution_event,logistics.tms_execution,"
                + "logistics.tms_idempotency,logistics.tms_dispatch_event,logistics.tms_resource_reservation,logistics.tms_assignment,logistics.tms_task_dispatch,"
                + "logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle,logistics.order_core,logistics.tom_order,"
                + "logistics.cargo_item,logistics.transport_task,logistics.task_dependency,logistics.order_draft_revision,logistics.api_idempotency,"
                + "logistics.crm_contact,logistics.crm_address,logistics.crm_customer,logistics.adm_change_log,logistics.upms_audit,logistics.upms_account");
        jdbc.update("DELETE FROM logistics.adm_dict_item WHERE NOT read_only");
        jdbc.update("UPDATE logistics.adm_dict_category SET enabled=true WHERE NOT read_only");
        clock.reset(); policy.mode = PolicyMode.SYNTHETIC_TEST_ONLY; evidencePolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;storage.reset();
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


    @ParameterizedTest @ValueSource(strings={"IMPORT","EXPORT","LOCAL"})
    void twoAdjacentProgressTransitionsUseExactVerifiedStageEvidenceWithoutChangingDispatchOrReleasingResources(String type)throws Exception {
        Ready ready=ready(type);Map<String,String> sourceBefore=dispatchAndSourceDigest();
        JsonNode view=company(driverBrowser.get(path(ready)+"/progress-policy"),200,"0000");
        policyView(view,ready,"IN_PROGRESS");
        byte[] original=png(2,2,0xff225588);clock.advance(Duration.ofNanos(987654321));
        Instant uploadedAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var uploadedReply=upload(ready,original,"1",key());JsonNode first=company(uploadedReply,200,"0000");
        evidence(first,ready,"DEPARTED",1,original);assertThat(uploadedReply.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(Instant.parse(first.get("uploadedAt").asString())).isEqualTo(uploadedAt);
        assertThat(company(driverBrowser.get(path(ready)),200,"0000").get("execution")).isEqualTo(ready.departure().get("execution"));
        assertThat(count("tms_execution_event")).isEqualTo(1);assertThat(count("tms_execution_command")).isEqualTo(1);
        assertThat(count("tms_execution_evidence")).isEqualTo(1);assertThat(count("tms_execution_evidence_object")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM logistics.tms_execution_evidence",UUID.class)).isEqualTo(driverAccount.id());
        assertThat(jdbc.queryForObject("SELECT encode(content,'hex') FROM logistics.tms_execution_evidence_object",String.class))
                .isEqualTo(java.util.HexFormat.of().formatHex(original));
        JsonNode progress=company(progress(ready,1,"IN_PROGRESS",List.of(id(first)),key()),200,"0000");
        progressResult(progress,ready,"IN_PROGRESS",2);assertThat(count("tms_execution_event_evidence")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT evidence_id FROM logistics.tms_execution_event_evidence",UUID.class)).isEqualTo(uuid(first));
        policyView(company(driverBrowser.get(path(ready)+"/progress-policy"),200,"0000"),ready,"AWAITING_COMPLETION");
        JsonNode second=company(upload(ready,png(3,1,0xffaacc33),"2",key()),200,"0000");
        evidence(second,ready,"IN_PROGRESS",2,png(3,1,0xffaacc33));
        JsonNode awaited=company(progress(ready,2,"AWAITING_COMPLETION",List.of(id(second)),key()),200,"0000");
        progressResult(awaited,ready,"AWAITING_COMPLETION",3);
        JsonNode unsupported=company(driverBrowser.get(path(ready)+"/progress-policy"),200,"0000");
        assertThat(unsupported.get("blocker").asString()).isEqualTo("UNSUPPORTED_PHASE");
        assertThat(unsupported.get("nextPhase").isNull()).isTrue();assertThat(unsupported.get("requirements").isNull()).isTrue();
        assertPrivateContent(content(driverBrowser,ready,id(first)),original);
        assertPrivateContent(content(driverBrowser,ready,id(second)),png(3,1,0xffaacc33));
        assertThat(count("tms_execution_event")).isEqualTo(3);assertThat(count("tms_execution_command")).isEqualTo(3);
        assertThat(count("tms_execution_event_evidence")).isEqualTo(2);
        assertThat(dispatchAndSourceDigest()).isEqualTo(sourceBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.tms_resource_reservation WHERE released_at IS NULL",Long.class)).isEqualTo(2);
        var detailFields=new ArrayList<>(MY_FIELDS);detailFields.add("execution");
        shape(company(driverBrowser.get(path(ready)),200,"0000"),detailFields);
        shape(company(driverBrowser.get("/tms/my-tasks"),200,"0000").get("content").get(0),MY_FIELDS);
    }

    @Test void metadataPaginationAndPrivateBytesArePureFreshReadsWithNoPublicStorageIdentity()throws Exception {
        Ready ready=ready("LOCAL");byte[] original=png(2,3,0xffaabbcc);JsonNode first=company(upload(ready,original,"1",key()),200,"0000");
        JsonNode second=company(upload(ready,original,"1",key()),200,"0000");Map<String,String> before=allDigest();
        JsonNode page=company(driverBrowser.get(path(ready)+"/evidence?size=1"),200,"0000");
        shape(page,List.of("content","page","size","totalElements","totalPages"));assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);assertThat(page.get("totalPages").asLong()).isEqualTo(2);
        var expectedIds=new ArrayList<>(List.of(id(first),id(second)));expectedIds.sort(String::compareTo);
        assertThat(page.get("content").get(0).get("id").asString()).isEqualTo(expectedIds.get(0));
        assertThat(company(driverBrowser.get(path(ready)+"/evidence?page=1&size=1"),200,"0000").get("content").get(0).get("id").asString()).isEqualTo(expectedIds.get(1));
        assertThat(company(driverBrowser.get(path(ready)+"/evidence?page=100000&size=1"),200,"0000").get("content")).isEmpty();
        for(String query:List.of("page=-1","page=100001","size=0","size=101","page=0&page=1","sort=id","driverId="+id(otherDriver)))
            company(driverBrowser.get(path(ready)+"/evidence?"+query),400,"E0400");
        var bytes=content(driverBrowser,ready,id(first));assertPrivateContent(bytes,original);
        var conditional=driverBrowser.request("GET",path(ready)+"/evidence/"+id(first)+"/content",null,null,null)
                .setHeader("Accept","image/png").header("If-None-Match","*").header("If-Modified-Since","Tue, 01 Jan 2030 00:00:00 GMT").build();
        assertPrivateContent(driverBrowser.client.send(conditional,HttpResponse.BodyHandlers.ofByteArray()),original);
        var ranged=driverBrowser.request("GET",path(ready)+"/evidence/"+id(first)+"/content",null,null,null).setHeader("Accept","image/png").header("Range","bytes=0-1").build();
        binaryError(driverBrowser.client.send(ranged,HttpResponse.BodyHandlers.ofByteArray()),400,"E0400");
        var head=driverBrowser.request("HEAD",path(ready)+"/evidence/"+id(first)+"/content",null,null,null).setHeader("Accept","image/png").build();
        assertThat(driverBrowser.client.send(head,HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(405);
        company(driverBrowser.get(path(ready)+"/progress-policy?unknown=true"),400,"E0400");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void originalUploadAndProgressReceiptsReplayAfterLaterPhaseAndCurrentPolicyProviderAreUnavailable()throws Exception {
        Ready ready=ready("LOCAL");byte[] image=png(1,1,0xff110022);String photoKey=key(),progressKey=key();
        JsonNode originalPhoto=company(upload(ready,image,"1",photoKey),200,"0000");
        JsonNode originalProgress=company(progress(ready,1,"IN_PROGRESS",List.of(id(originalPhoto)),progressKey),200,"0000");
        evidencePolicy.mode=PolicyMode.UNCONFIRMED;storage.available=false;policy.mode=PolicyMode.UNCONFIRMED;
        int providerReads=storage.loads.get();Map<String,String> before=allDigest();
        var photoReplay=upload(ready,image,"1",photoKey);assertThat(company(photoReplay,200,"0000")).isEqualTo(originalPhoto);
        assertThat(photoReplay.headers().firstValue("Idempotency-Replayed")).contains("true");
        var progressReplay=progress(ready,1,"IN_PROGRESS",List.of(id(originalPhoto)),progressKey);
        assertThat(company(progressReplay,200,"0000")).isEqualTo(originalProgress);assertThat(progressReplay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(storage.loads.get()).isEqualTo(providerReads);
        assertThat(company(driverBrowser.get(path(ready)+"/evidence"),200,"0000").get("content")).hasSize(1);
        JsonNode blocked=company(driverBrowser.get(path(ready)+"/progress-policy"),200,"0000");
        assertThat(blocked.get("blocker").asString()).isEqualTo("POLICY_NOT_CONFIRMED");assertThat(blocked.get("nextPhase").isNull()).isTrue();assertThat(blocked.get("requirements").isNull()).isTrue();
        binaryBusiness(content(driverBrowser,ready,id(originalPhoto)),"E1803","POLICY_NOT_CONFIRMED");
        business(upload(ready,png(1,1,0xff110023),"1",photoKey),"E1804","IDEMPOTENCY_KEY_REUSED");
        business(upload(ready,image,"2",photoKey),"E1804","IDEMPOTENCY_KEY_REUSED");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void evidenceIdentitySetIsCanonicalButDuplicatesAreRejectedAndNewKeysDoNotContentDeduplicate()throws Exception {
        Ready ready=ready("LOCAL");byte[] image=png(1,2,0xff334455);
        JsonNode one=company(upload(ready,image,"1",key()),200,"0000"),two=company(upload(ready,image,"1",key()),200,"0000");
        assertThat(id(one)).isNotEqualTo(id(two));assertThat(one.get("sha256")).isEqualTo(two.get("sha256"));
        String commandKey=key();
        JsonNode original=company(driverBrowser.write("POST",path(ready)+"/progress",fields("expectedVersion",new java.math.BigDecimal("1.00"),"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(two).toUpperCase(java.util.Locale.ROOT),id(one))),commandKey),200,"0000");
        Map<String,String> before=allDigest();
        String uppercasePath="/tms/my-tasks/"+id(ready.assignment()).toUpperCase(java.util.Locale.ROOT)+"/progress";
        var replay=driverBrowser.write("POST",uppercasePath,fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(one).toUpperCase(java.util.Locale.ROOT),id(two))),commandKey);
        assertThat(company(replay,200,"0000")).isEqualTo(original);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        company(progress(ready,1,"IN_PROGRESS",List.of(id(one),id(one).toUpperCase(java.util.Locale.ROOT)),key()),400,"E0400");
        business(progress(ready,1,"IN_PROGRESS",List.of(id(one)),commandKey),"E1804","IDEMPOTENCY_KEY_REUSED");
        var expected=new ArrayList<>(List.of(id(one),id(two)));expected.sort(String::compareTo);
        assertThat(jdbc.queryForList("SELECT evidence_id::text FROM logistics.tms_execution_event_evidence ORDER BY input_position",String.class)).containsExactlyElementsOf(expected);
        assertThat(allDigest()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"POLICY","PROVIDER","DISPATCH"})
    void eachUnconfirmedCurrentDependencyIndependentlyRefusesNewWritesWithoutPretendingPhotosAreOptional(String dependency)throws Exception {
        Ready ready=ready("LOCAL");JsonNode photo=company(upload(ready,png(1,1,0xffabcdef),"1",key()),200,"0000");
        if(dependency.equals("POLICY"))evidencePolicy.mode=PolicyMode.UNCONFIRMED;
        else if(dependency.equals("PROVIDER"))storage.available=false;else policy.mode=PolicyMode.UNCONFIRMED;
        Map<String,String> before=allDigest();
        business(upload(ready,png(1,1,0xffabcdee),"1",key()),"E1803","POLICY_NOT_CONFIRMED");
        business(progress(ready,1,"IN_PROGRESS",List.of(id(photo)),key()),"E1803","POLICY_NOT_CONFIRMED");
        JsonNode blocked=company(driverBrowser.get(path(ready)+"/progress-policy"),200,"0000");
        assertThat(blocked.get("blocker").asString()).isEqualTo("POLICY_NOT_CONFIRMED");assertThat(blocked.get("requirements").isNull()).isTrue();
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void strictProgressJsonAndRawUploadMetadataCannotSelectIdentityPhaseRequirementsOrProvider()throws Exception {
        Ready ready=ready("LOCAL");Map<String,String> before=allDigest();String token=driverBrowser.csrf(),progressPath=path(ready)+"/progress";
        for(String payload:List.of("null","[]","{}","{\"expectedVersion\":1,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[],\"requirementsMet\":true}",
                "{\"expectedVersion\":1,\"expectedVersion\":1,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[]}",
                "{\"expectedVersion\":1,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[]} {}",
                "{\"expectedVersion\":1.1,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[]}",
                "{\"expectedVersion\":9007199254740992,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[]}",
                "{\"expectedVersion\":1,\"targetPhase\":\"COMPLETED\",\"evidenceIds\":[]}",
                "{\"expectedVersion\":1,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[\"not-uuid\"]}",
                "{\"expectedVersion\":1,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[],\"\\ud800\":true}"))
            company(driverBrowser.send("POST",progressPath,payload,key(),token),400,"E0400");
        company(progress(ready,1,"IN_PROGRESS",java.util.Collections.nCopies(33,key()),key()),400,"E0400");
        for(String bad:List.of("","01","+1","-1","1e0","1.0","9007199254740992","1,1"))
            company(rawUpload(driverBrowser,ready,png(1,1,0xff123456),bad,key(),"image/png",null),400,"E0400");
        company(rawUpload(driverBrowser,ready,png(1,1,0xff123456),null,key(),"image/png",null),400,"E0400");
        var duplicateVersion=photoRequest(driverBrowser,ready,png(1,1,0xff123456),null,key(),"image/png",token).header("X-Execution-Version","1").header("X-Execution-Version","1").build();
        company(driverBrowser.client.send(duplicateVersion,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        var duplicateMedia=photoRequest(driverBrowser,ready,png(1,1,0xff123456),"1",key(),"image/png",token).header("Content-Type","image/png").build();
        company(driverBrowser.client.send(duplicateMedia,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        company(rawUpload(driverBrowser,ready,png(1,1,0xff123456),"1",null,"image/png",null),400,"E0400");
        company(rawUpload(driverBrowser,ready,png(1,1,0xff123456),"1","invalid key","image/png",null),400,"E0400");
        var duplicateKey=photoRequest(driverBrowser,ready,png(1,1,0xff123456),"1",key(),"image/png",token).header("Idempotency-Key",key()).build();
        company(driverBrowser.client.send(duplicateKey,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        var uploadQuery=photoRequest(driverBrowser,ready,png(1,1,0xff123456),"1",key(),"image/png",token).uri(uri(path(ready)+"/evidence?phase=DEPARTED")).build();
        company(driverBrowser.client.send(uploadQuery,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        byte[] invalidUtf8=new byte[]{'{','"','x','"',':','"',(byte)0xc3,(byte)0x28,'"','}'};
        var invalidProgress=driverBrowser.request("POST",progressPath,invalidUtf8,"application/json",token).header("Idempotency-Key",key()).build();
        company(driverBrowser.client.send(invalidProgress,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        for(String media:List.of("image/jpeg","application/json","multipart/form-data","image/png; charset=utf-8"))
            company(rawUpload(driverBrowser,ready,png(1,1,0xff123456),"1",key(),media,null),415,"E0415");
        byte[] multipart;
        try(var body=new java.io.ByteArrayOutputStream()){
            body.write("--synthetic-photo-boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            body.write(png(1,1,0xff123456));body.write("\r\n--synthetic-photo-boundary--\r\n".getBytes(StandardCharsets.US_ASCII));multipart=body.toByteArray();
        }
        company(rawUpload(driverBrowser,ready,multipart,"1",key(),"multipart/form-data; boundary=synthetic-photo-boundary",null),415,"E0415");
        company(rawUpload(driverBrowser,ready,new byte[]{'-','-'},"1",key(),"multipart/form-data; boundary=synthetic-photo-boundary",null),415,"E0415");
        byte[] form="phase=DEPARTED&photo=synthetic".getBytes(StandardCharsets.US_ASCII);
        company(rawUpload(driverBrowser,ready,form,"1",key(),"application/x-www-form-urlencoded",null),415,"E0415");
        Browser anonymous=browser();
        for(var untrusted:List.of(Map.entry("multipart/form-data; boundary=synthetic-photo-boundary",multipart),
                Map.entry("multipart/form-data; boundary=synthetic-photo-boundary",new byte[]{'-','-'}),
                Map.entry("application/x-www-form-urlencoded",form))){
            var missingCsrf=photoRequest(driverBrowser,ready,untrusted.getValue(),"1",key(),untrusted.getKey(),null).build();
            assertThat(company(driverBrowser.client.send(missingCsrf,HttpResponse.BodyHandlers.ofString()),403,"E0403").get("reason").asString()).isEqualTo("CSRF_INVALID");
            var anonymousRequest=photoRequest(anonymous,ready,untrusted.getValue(),"1",key(),untrusted.getKey(),null).build();
            assertThat(company(anonymous.client.send(anonymousRequest,HttpResponse.BodyHandlers.ofString()),401,"E0401").isNull()).isTrue();
        }
        company(rawUpload(driverBrowser,ready,png(1,1,0xff123456),"1",key(),"image/png","gzip"),415,"E0415");
        var duplicateEncoding=photoRequest(driverBrowser,ready,png(1,1,0xff123456),"1",key(),"image/png",token).header("Content-Encoding","identity").header("Content-Encoding","identity").build();
        company(driverBrowser.client.send(duplicateEncoding,HttpResponse.BodyHandlers.ofString()),400,"E0400");
        company(driverBrowser.send("POST",progressPath+"?driverId="+id(otherDriver),"{\"expectedVersion\":1,\"targetPhase\":\"IN_PROGRESS\",\"evidenceIds\":[]}",key(),token),400,"E0400");
        company(driverBrowser.get(path(ready)+"/evidence?phase=DEPARTED"),400,"E0400");
        assertThat(allDigest()).isEqualTo(before);noEvidence();
    }

    @Test void actualPngSignatureCrcDecodeDimensionsAndByteRulesRejectWithoutLeavingObjectsOrReceipts()throws Exception {
        Ready ready=ready("LOCAL");Map<String,String> before=allDigest();byte[] valid=png(1,1,0xff112233),crc=valid.clone();crc[crc.length-1]^=1;
        for(byte[] invalid:List.of(new byte[0],new byte[]{1,2,3},java.util.Arrays.copyOf(valid,20),crc,png(65,65,0xff112233),paddedPng(65537))) {
            JsonNode error=company(upload(ready,invalid,"1",key()),400,"E0400");
            assertThat(error.get("reason").asString()).isEqualTo("VALIDATION_ERROR");assertThat(error.get("fields").has("photo")).isTrue();
        }
        for(boolean chunked:List.of(false,true)) {
            byte[] overflow=new byte[1048577];var builder=photoRequest(driverBrowser,ready,overflow,"1",key(),"image/png",driverBrowser.csrf());
            if(chunked)builder.POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(overflow)));
            assertThat(company(driverBrowser.client.send(builder.build(),HttpResponse.BodyHandlers.ofString()),413,"E0413").isNull()).isTrue();
        }
        assertThat(allDigest()).isEqualTo(before);noEvidence();
        byte[] exact=paddedPng(65536);JsonNode photo=company(upload(ready,exact,"1",key()),200,"0000");evidence(photo,ready,"DEPARTED",1,exact);
        assertPrivateContent(content(driverBrowser,ready,id(photo)),exact);
    }

    @Test void evidenceProofMustBeExplicitVerifiedCurrentStageAndAssignmentWhileEmptySelectionIsDistinct()throws Exception {
        Ready ready=ready("LOCAL");Map<String,String> before=allDigest();
        business(progress(ready,1,"IN_PROGRESS",List.of(),key()),"E1807","EVIDENCE_REQUIRED");
        business(progress(ready,1,"AWAITING_COMPLETION",List.of(),key()),"E1802","INVALID_STATE");
        business(progress(ready,1,"IN_PROGRESS",List.of(key()),key()),"E1808","EVIDENCE_INVALID");
        assertThat(allDigest()).isEqualTo(before);
        JsonNode first=company(upload(ready,png(1,1,0xffaabbcc),"1",key()),200,"0000");
        company(progress(ready,1,"IN_PROGRESS",List.of(id(first)),key()),200,"0000");
        before=allDigest();business(progress(ready,2,"AWAITING_COMPLETION",List.of(id(first)),key()),"E1808","EVIDENCE_INVALID");
        business(upload(ready,png(1,1,0xffaabbcc),"1",key()),"E1801","VERSION_CONFLICT");
        assertThat(allDigest()).isEqualTo(before);
    }

    @Test void currentReboundDriverMayUseHistoricalUploaderEvidenceButOldActorCannotReadOrReplay()throws Exception {
        Ready ready=ready("LOCAL");byte[] bytes=png(1,2,0xff778899);String photoKey=key();JsonNode photo=company(upload(ready,bytes,"1",photoKey),200,"0000");
        Account replacement=account("DRIVER",false);Browser replacementBrowser=login(replacement);
        company(admin.write("PATCH","/fms/drivers/"+id(driver),fields("version",0,"accountId",replacement.id().toString()),null),200,"0000");
        assertThat(company(driverBrowser.get(path(ready)+"/evidence"),404,"E0404").isNull()).isTrue();
        assertThat(company(rawUpload(driverBrowser,ready,bytes,"1",photoKey,"image/png",null),404,"E0404").isNull()).isTrue();
        assertThat(company(replacementBrowser.get(path(ready)+"/evidence"),200,"0000").get("content").get(0)).isEqualTo(photo);
        assertPrivateContent(content(replacementBrowser,ready,id(photo)),bytes);
        company(replacementBrowser.write("POST",path(ready)+"/progress",fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(photo))),key()),200,"0000");
    }

    @Test void managementAndForeignDriverCannotReadMetadataPrivateBytesOrDriveAnyOfTheFiveOperations()throws Exception {
        Ready ready=ready("LOCAL");JsonNode photo=company(upload(ready,png(1,1,0xff445566),"1",key()),200,"0000");
        for(Browser forbidden:List.of(admin,login(account("DISPATCHER",false)),otherBrowser)) {
            int status=forbidden==otherBrowser?404:403;String code=status==404?"E0404":"E0403";
            company(forbidden.get(path(ready)+"/progress-policy"),status,code);company(forbidden.get(path(ready)+"/evidence"),status,code);
            binaryError(content(forbidden,ready,id(photo)),status,code);
            company(forbidden.write("POST",path(ready)+"/progress",fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(photo))),key()),status,code);
            company(rawUpload(forbidden,ready,png(1,1,0xff334455),"1",key(),"image/png",null),status,code);
        }
        binaryError(content(driverBrowser,ready,key()),404,"E0404");
    }

    @ParameterizedTest @ValueSource(strings={"POLICY","PROGRESS","UPLOAD","LIST","CONTENT"})
    void logoutAheadOfQueuedEvidenceOperationInvalidatesCapturedSessionBeforeDataReceiptOrBytes(String operation)throws Exception {
        Ready ready=ready("LOCAL");JsonNode photo=company(upload(ready,png(1,1,0xff334455),"1",key()),200,"0000");
        String token=driverBrowser.csrf();Map<String,String> before=allDigest();List<HttpResponse<byte[]>> results;
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()) {
            held.setAutoCommit(false);canonical(held);
            var logout=pool.submit(()->driverBrowser.client.send(driverBrowser.request("POST","/auth/logout","{}".getBytes(StandardCharsets.UTF_8),"application/json",token).build(),HttpResponse.BodyHandlers.ofByteArray()));
            try {
                awaitLockCount("upms_lock",1);
                var pending=pool.submit(()->queuedEvidenceRequest(ready,photo,operation,token));
                try{awaitLockCount("upms_lock",2);}finally{held.rollback();}
                results=List.of(logout.get(20,TimeUnit.SECONDS),pending.get(20,TimeUnit.SECONDS));
            }finally{held.rollback();}
        }
        binaryError(results.get(0),200,"0000");binaryError(results.get(1),401,"E0401");
        assertThat(results.get(1).headers().firstValue("Idempotency-Replayed")).isEmpty();assertThat(allDigest()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"tms_execution_evidence","tms_execution_evidence_object","tms_execution_evidence_command"})
    void eachUploadStoreFailureRollsBackBlobMetadataAndReceiptAndOriginalKeyCanRetry(String table)throws Exception {
        Ready ready=ready("LOCAL");Map<String,String> before=allDigest();String commandKey=key();byte[] bytes=png(1,1,0xff456789);failInsert(table);
        try{assertThat(company(upload(ready,bytes,"1",commandKey),500,"E0500").isNull()).isTrue();}finally{dropFailure(table);}
        assertThat(allDigest()).isEqualTo(before);noEvidence();company(upload(ready,bytes,"1",commandKey),200,"0000");
        assertThat(count("tms_execution_evidence")).isEqualTo(1);assertThat(count("tms_execution_evidence_object")).isEqualTo(1);assertThat(count("tms_execution_evidence_command")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"tms_execution","tms_execution_event","tms_execution_event_evidence","tms_execution_command"})
    void eachProgressWriteFailureKeepsOriginalPhotoAndEntirePriorExecutionUnchanged(String table)throws Exception {
        Ready ready=ready("LOCAL");JsonNode photo=company(upload(ready,png(1,1,0xff765432),"1",key()),200,"0000");Map<String,String> before=allDigest();String commandKey=key();
        failAt(table,table.equals("tms_execution")?"BEFORE UPDATE":"BEFORE INSERT");
        try{assertThat(company(progress(ready,1,"IN_PROGRESS",List.of(id(photo)),commandKey),500,"E0500").isNull()).isTrue();}finally{dropFailure(table);}
        assertThat(allDigest()).isEqualTo(before);company(progress(ready,1,"IN_PROGRESS",List.of(id(photo)),commandKey),200,"0000");
        assertThat(count("tms_execution_event")).isEqualTo(2);assertThat(count("tms_execution_event_evidence")).isEqualTo(1);assertThat(count("tms_execution_evidence_object")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"tms_execution_evidence","tms_execution_evidence_command"})
    void deferredUploaderForeignKeyFailureUsesEvidenceErrorNamespaceAndLeavesNoOrphanBytes(String table)throws Exception {
        Ready ready=ready("LOCAL");Map<String,String> before=allDigest();String commandKey=key();
        jdbc.execute("CREATE FUNCTION logistics.test_execution_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN SET CONSTRAINTS ALL DEFERRED; NEW.actor_id='00000000-0000-4000-8000-000000009999'; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_execution_fail BEFORE INSERT ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_fail()");
        try{business(upload(ready,png(1,1,0xff556677),"1",commandKey),"E1806","PROTECTED_RELATION");}finally{dropFailure(table);}
        assertThat(allDigest()).isEqualTo(before);noEvidence();
    }

    @Test void transientProviderFailureIsSanitizedAndStoredPhotosEventsLinksAndReceiptsAreImmutable()throws Exception {
        Ready ready=ready("LOCAL");Map<String,String> before=allDigest();storage.failStore=true;
        assertThat(company(upload(ready,png(1,1,0xff101010),"1",key()),500,"E0500").isNull()).isTrue();storage.failStore=false;
        assertThat(allDigest()).isEqualTo(before);JsonNode photo=company(upload(ready,png(1,1,0xff101010),"1",key()),200,"0000");
        storage.failLoad=true;binaryError(content(driverBrowser,ready,id(photo)),500,"E0500");
        assertThat(company(progress(ready,1,"IN_PROGRESS",List.of(id(photo)),key()),500,"E0500").isNull()).isTrue();storage.failLoad=false;
        company(progress(ready,1,"IN_PROGRESS",List.of(id(photo)),key()),200,"0000");before=allDigest();
        for(String table:EVIDENCE_TABLES) {
            assertThatThrownBy(()->jdbc.update("DELETE FROM logistics."+table)).isInstanceOf(DataAccessException.class);
            String mutation=table.equals("tms_execution_evidence_object")?"content=content||decode('00','hex')":table.equals("tms_execution_evidence")?"byte_size=byte_size+1":table.equals("tms_execution_event_evidence")?"input_position=input_position+1":"original_data='{}'::json";
            assertThatThrownBy(()->jdbc.update("UPDATE logistics."+table+" SET "+mutation)).isInstanceOf(DataAccessException.class);
        }
        assertThat(allDigest()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"UPLOAD","PROGRESS"})
    void sameKeyInFlightIsBoundedBeforeCanonicalQueueAndCommitsOnlyOneBusinessResult(String operation)throws Exception {
        Ready ready=ready("LOCAL");byte[] bytes=png(1,1,0xff998877);JsonNode photo=operation.equals("PROGRESS")?company(upload(ready,bytes,"1",key()),200,"0000"):null;
        Browser another=login(driverAccount);String firstToken=driverBrowser.csrf(),secondToken=another.csrf(),commandKey=key();String table=operation.equals("UPLOAD")?"tms_execution_evidence_object":"tms_execution_event";
        pause(table,"BEFORE INSERT");
        try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()) {
            advisory(held,true);var first=pool.submit(()->operation.equals("UPLOAD")?rawUploadWithToken(driverBrowser,ready,bytes,"1",commandKey,firstToken):driverBrowser.send("POST",path(ready)+"/progress",json.writeValueAsString(fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(photo)))),commandKey,firstToken));
            try{awaitLock(table);long start=System.nanoTime();
                var duplicate=operation.equals("UPLOAD")?rawUploadWithToken(another,ready,bytes,"1",commandKey,secondToken):another.send("POST",path(ready)+"/progress",json.writeValueAsString(fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(photo)))),commandKey,secondToken);
                business(duplicate,"E1805","IDEMPOTENCY_IN_PROGRESS");assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(15));
            }finally{advisory(held,false);}company(first.get(20,TimeUnit.SECONDS),200,"0000");
        }finally{unpause(table);}
        assertThat(count("tms_execution_evidence")).isEqualTo(1);assertThat(count("tms_execution_evidence_command")).isEqualTo(1);
        assertThat(count("tms_execution_event")).isEqualTo(operation.equals("PROGRESS")?2:1);
    }

    @Test void differentUploadKeysSerializeWithoutBumpingExecutionVersionOrContentDeduplication()throws Exception {
        Ready ready=ready("LOCAL");byte[] bytes=png(1,1,0xff010203);String token=driverBrowser.csrf();List<HttpResponse<String>> replies;
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()) {
            held.setAutoCommit(false);canonical(held);
            var one=pool.submit(()->rawUploadWithToken(driverBrowser,ready,bytes,"1",key(),token));var two=pool.submit(()->rawUploadWithToken(driverBrowser,ready,bytes,"1",key(),token));
            try{awaitLockCount("upms_lock",2);}finally{held.rollback();}replies=List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
        }
        JsonNode one=company(replies.get(0),200,"0000"),two=company(replies.get(1),200,"0000");assertThat(id(one)).isNotEqualTo(id(two));
        assertThat(company(driverBrowser.get(path(ready)),200,"0000").get("execution").get("version").asLong()).isEqualTo(1);
        assertThat(count("tms_execution_evidence_object")).isEqualTo(2);assertThat(count("tms_execution_event")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"PROGRESS_FIRST","UPLOAD_FIRST"})
    void uploadAndProgressSerializeAtTheExactSourcePhaseWithoutAttachingOldVersionToTheNextStage(String order)throws Exception {
        Ready ready=ready("LOCAL");JsonNode proof=company(upload(ready,png(1,1,0xffa0b0c0),"1",key()),200,"0000");String token=driverBrowser.csrf();
        String table=order.equals("PROGRESS_FIRST")?"tms_execution_event":"tms_execution_evidence_command";pause(table,"BEFORE INSERT");
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()) {
            advisory(held,true);
            var first=pool.submit(()->order.equals("PROGRESS_FIRST")?driverBrowser.send("POST",path(ready)+"/progress",json.writeValueAsString(fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(proof)))),key(),token):rawUploadWithToken(driverBrowser,ready,png(1,1,0xffa0b0c1),"1",key(),token));
            try{awaitLock(table);
                var second=pool.submit(()->order.equals("PROGRESS_FIRST")?rawUploadWithToken(driverBrowser,ready,png(1,1,0xffa0b0c1),"1",key(),token):driverBrowser.send("POST",path(ready)+"/progress",json.writeValueAsString(fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(proof)))),key(),token));
                awaitLock("upms_lock");advisory(held,false);company(first.get(20,TimeUnit.SECONDS),200,"0000");
                var later=second.get(20,TimeUnit.SECONDS);if(order.equals("PROGRESS_FIRST"))business(later,"E1801","VERSION_CONFLICT");else{JsonNode saved=company(later,200,"0000");progressResult(saved,ready,"IN_PROGRESS",2);}
            }finally{advisory(held,false);}
        }finally{unpause(table);}
        assertThat(count("tms_execution_evidence")).isEqualTo(order.equals("PROGRESS_FIRST")?1:2);
        assertThat(count("tms_execution_event_evidence")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT evidence_id FROM logistics.tms_execution_event_evidence",UUID.class)).isEqualTo(uuid(proof));
    }


    @Test void waitingPhaseHasNoInventedRequirementsAndCannotSkipDeparture()throws Exception {
        JsonNode assignment=assigned("LOCAL",driver,main);Ready waiting=new Ready(assignment,json.createObjectNode());Map<String,String> before=allDigest();
        JsonNode policyView=company(driverBrowser.get(path(waiting)+"/progress-policy"),200,"0000");
        assertThat(policyView.get("mode").asString()).isEqualTo("SYNTHETIC_TEST_ONLY");assertThat(policyView.get("blocker").asString()).isEqualTo("UNSUPPORTED_PHASE");
        assertThat(policyView.get("nextPhase").isNull()).isTrue();assertThat(policyView.get("requirements").isNull()).isTrue();
        business(upload(waiting,png(1,1,0xff998877),"0",key()),"E1802","INVALID_STATE");
        business(progress(waiting,0,"IN_PROGRESS",List.of(),key()),"E1802","INVALID_STATE");assertThat(allDigest()).isEqualTo(before);
    }

    @Test void foreignEvidenceIsOpaqueAndCannotBeSelectedAsCurrentAssignmentProof()throws Exception {
        Ready own=ready("LOCAL");JsonNode otherAssignment=assigned("LOCAL",otherDriver,otherMain);
        JsonNode otherDeparture=company(otherBrowser.write("POST",departPath(otherAssignment),fields("expectedVersion",0),key()),200,"0000");
        Ready other=new Ready(otherAssignment,otherDeparture);JsonNode foreign=company(rawUpload(otherBrowser,other,png(1,1,0xff112244),"1",key(),"image/png",null),200,"0000");
        Map<String,String> before=allDigest();JsonNode error=business(progress(own,1,"IN_PROGRESS",List.of(id(foreign)),key()),"E1808","EVIDENCE_INVALID");
        assertThat(error.propertyNames()).containsExactly("reason");binaryError(content(driverBrowser,own,id(foreign)),404,"E0404");assertThat(allDigest()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"SOURCE","OCCUPATION"})
    void staleCurrentProofPrecedesEveryStoredUploadOrProgressReceipt(String invalidation)throws Exception {
        Ready ready=ready("LOCAL");byte[] bytes=png(1,1,0xffbb2244);String uploadKey=key(),progressKey=key();
        JsonNode photo=company(upload(ready,bytes,"1",uploadKey),200,"0000");company(progress(ready,1,"IN_PROGRESS",List.of(id(photo)),progressKey),200,"0000");
        if(invalidation.equals("SOURCE"))jdbc.update("UPDATE logistics.tom_order SET version=version+1 WHERE order_id=?",UUID.fromString(ready.assignment().get("orderId").asString()));
        else jdbc.update("UPDATE logistics.tms_resource_reservation SET released_at=CURRENT_TIMESTAMP,release_reason='合成占用缺失' WHERE assignment_id=? AND position='MAIN'",uuid(ready.assignment()));
        Map<String,String> before=allDigest();
        assertThat(company(upload(ready,bytes,"1",uploadKey),404,"E0404").isNull()).isTrue();
        assertThat(company(progress(ready,1,"IN_PROGRESS",List.of(id(photo)),progressKey),404,"E0404").isNull()).isTrue();
        company(driverBrowser.get(path(ready)+"/progress-policy"),404,"E0404");company(driverBrowser.get(path(ready)+"/evidence"),404,"E0404");
        binaryError(content(driverBrowser,ready,id(photo)),404,"E0404");assertThat(allDigest()).isEqualTo(before);
    }

    @Test void competingDifferentProgressKeysCanCommitOnlyOnePhaseVersionAndOneSelectedLink()throws Exception {
        Ready ready=ready("LOCAL");JsonNode photo=company(upload(ready,png(1,1,0xff667788),"1",key()),200,"0000");String token=driverBrowser.csrf();
        String payload=json.writeValueAsString(fields("expectedVersion",1,"targetPhase","IN_PROGRESS","evidenceIds",List.of(id(photo))));List<HttpResponse<String>> results;
        try(var pool=Executors.newFixedThreadPool(2);var held=dataSource.getConnection()){
            held.setAutoCommit(false);canonical(held);var one=pool.submit(()->driverBrowser.send("POST",path(ready)+"/progress",payload,key(),token));var two=pool.submit(()->driverBrowser.send("POST",path(ready)+"/progress",payload,key(),token));
            try{awaitLockCount("upms_lock",2);}finally{held.rollback();}results=List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
        }
        assertThat(results.stream().filter(reply->reply.statusCode()==200).count()).isEqualTo(1);
        for(var result:results)if(result.statusCode()==200)company(result,200,"0000");else business(result,"E1801","VERSION_CONFLICT");
        assertThat(count("tms_execution_event")).isEqualTo(2);assertThat(count("tms_execution_command")).isEqualTo(2);assertThat(count("tms_execution_event_evidence")).isEqualTo(1);
    }

    @Test void databaseRejectsWrongContentDigestOrphanPrivateBytesAndCrossStageEvidenceAssociation()throws Exception {
        Ready ready=ready("LOCAL");byte[] image=png(1,1,0xff112255);JsonNode initial=company(upload(ready,image,"1",key()),200,"0000");
        company(progress(ready,1,"IN_PROGRESS",List.of(id(initial)),key()),200,"0000");JsonNode later=company(upload(ready,image,"2",key()),200,"0000");Map<String,String> before=allDigest();
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.tms_execution_evidence_object(id,content,byte_size,sha256) VALUES (?,?,?,?)",UUID.randomUUID(),image,image.length,"0".repeat(64))).isInstanceOf(DataAccessException.class);
        String correct=hash(image);
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.tms_execution_evidence_object(id,content,byte_size,sha256) VALUES (?,?,?,?)",UUID.randomUUID(),image,image.length,correct)).isInstanceOf(DataAccessException.class);
        UUID event=jdbc.queryForObject("SELECT id FROM logistics.tms_execution_event WHERE assignment_id=? AND to_version=2",UUID.class,uuid(ready.assignment()));
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.tms_execution_event_evidence(id,event_id,evidence_id,assignment_id,input_position,from_phase,from_version) VALUES (?,?,?,?,1,'IN_PROGRESS',2)",UUID.randomUUID(),event,uuid(later),uuid(ready.assignment()))).isInstanceOf(DataAccessException.class);
        assertThat(allDigest()).isEqualTo(before);
    }

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
    private JsonNode binaryError(HttpResponse<byte[]> response,int status,String code){assertThat(response.statusCode()).isEqualTo(status);assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");JsonNode body=json.readTree(response.body());shape(body,List.of("code","message","data","timestamp"));assertThat(body.get("code").asString()).isEqualTo(code);assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));if(response.uri().getPath().startsWith("/api/v1/tms/my-tasks/"))privateHeaders(response);return body.get("data");}
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
    private Map<String,String> allDigest(){var result=dispatchAndSourceDigest();for(String table:EVIDENCE_TABLES)result.put(table,digest(table,table.equals("tms_execution_event_evidence")?"event_id,evidence_id":"id"));for(String table:EXECUTION_TABLES)result.put(table,digest(table,table.equals("tms_execution")?"assignment_id":"id"));return result;}
    private Map<String,String> dispatchAndSourceDigest(){var result=new LinkedHashMap<String,String>();for(String table:DISPATCH_TABLES)result.put(table,digest(table,table.equals("tms_task_dispatch")?"task_id":"id"));for(String table:List.of("fms_driver","fms_vehicle","order_core","tom_order","cargo_item","transport_task","task_dependency","order_draft_revision","api_idempotency","order_acceptance_event","crm_customer","crm_contact","crm_address"))result.put(table,digest(table,table.equals("tom_order")?"order_id":table.equals("task_dependency")?"task_id,predecessor_task_id":"id"));return result;}
    private String digest(String table,String ordering){return jdbc.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+ordering+")::text,'[]')) FROM logistics."+table+" t",String.class);}
    private void shape(JsonNode value,List<String> expected){assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(expected);}
    private JsonNode company(HttpResponse<String> reply,int status,String code){if(reply.uri().getPath().matches("/api/v1/tms/my-tasks/[^/]+/(progress-policy|progress|evidence)(/.*)?"))privateHeaders(reply);JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).as("Execution HTTP status; code %s",body.get("code").asString()).isEqualTo(status);shape(body,List.of("code","message","data","timestamp"));assertThat(body.get("code").asString()).isEqualTo(code);assertThat(body.get("message").isString()).isTrue();assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));return body.get("data");}
    private JsonNode business(HttpResponse<String> reply,String code,String reason){JsonNode error=company(reply,409,code);assertThat(error.get("reason").asString()).isEqualTo(reason);return error;}
    private JsonNode tom(HttpResponse<String> reply,int status){JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).isEqualTo(status);TomContract.assertSchema("OrderEnvelope",body);return body.get("data");}
    private Account account(String role,boolean temporary)throws Exception{String username="exec-test-"+key().substring(0,12),password=UpmsTestData.password();try(var connection=dataSource.getConnection()){return new Account(UpmsTestData.insert(connection,encoder,username,role,password,temporary,clock.instant()),username,password);}}
    private Browser browser(){var browser=new Browser();browsers.add(browser);return browser;}
    private Browser login(Account account)throws Exception{var browser=browser();company(browser.write("POST","/auth/login",fields("username",account.username(),"password",account.password()),null),200,"0000");return browser;}
    private void canonical(Connection connection)throws Exception{try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();}}
    private void awaitLock(String fragment)throws Exception{awaitLockCount(fragment,1);}
    private void awaitLockCount(String fragment,int minimum)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);try(var observer=dataSource.getConnection();var statement=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")){observer.setAutoCommit(true);statement.setQueryTimeout(2);statement.setString(1,"%"+fragment+"%");while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getLong(1)>=minimum)return;}Thread.sleep(20);}}throw new AssertionError("Expected PostgreSQL execution lock wait for "+fragment);}
    private void pause(String table,String event){jdbc.execute("CREATE FUNCTION logistics.test_execution_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202642,1); RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_execution_pause "+event+" ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_execution_pause()");}
    private void unpause(String table){jdbc.execute("DROP TRIGGER test_execution_pause ON logistics."+table);jdbc.execute("DROP FUNCTION logistics.test_execution_pause()");}
    private void advisory(Connection connection,boolean acquire)throws Exception{try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute("SELECT pg_advisory_"+(acquire?"lock":"unlock")+"(202642,1)");}}
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
