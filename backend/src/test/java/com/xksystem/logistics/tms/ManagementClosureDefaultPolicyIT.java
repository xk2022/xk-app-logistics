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
@Import({com.xksystem.logistics.support.SyntheticDispatchPolicyConfiguration.class,com.xksystem.logistics.support.SyntheticEvidenceConfiguration.class,com.xksystem.logistics.support.SyntheticCompletionConfiguration.class,ManagementClosureDefaultPolicyIT.FixtureConfiguration.class})
class ManagementClosureDefaultPolicyIT {
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
    /** Only this management port is temporarily enabled to construct authentic administrative fixtures. Closure is never overridden. */
    @TestConfiguration static class FixtureConfiguration {
        @Bean @Primary FixtureManagementPolicy syntheticDefaultFixtureManagementPolicy(DataSource dataSource)throws Exception{return new FixtureManagementPolicy(SyntheticManagementPolicies.confirmation(dataSource));}
    }
    static final class FixtureManagementPolicy implements ManagementCompletionPolicy {
        final ManagementCompletionPolicy delegate;volatile PolicyMode mode=PolicyMode.UNCONFIRMED;
        FixtureManagementPolicy(ManagementCompletionPolicy delegate){this.delegate=delegate;}
        public PolicyMode mode(){return mode;}
        public boolean allows(TaskQueries.Source source,TmsDtos.DriverSnapshot driver){return delegate.allows(source,driver);}
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired java.time.Clock clock;
    @Autowired DispatchEligibilityPolicy policy;
    @Autowired ExecutionEvidencePolicy evidencePolicy;
    @Autowired EvidenceStorage storage;
    @Autowired ExceptionPolicy exceptionPolicy;
    @Autowired CompletionPolicy completionPolicy;
    @Autowired ManagementCompletionPolicy managementPolicy;
    @Autowired OrderClosurePolicy closurePolicy;
    @Autowired ExceptionQueries blockers;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired TaskQueries taskSources;
    @Autowired FixtureManagementPolicy fixtureManagementPolicy;
    @Autowired org.springframework.context.ApplicationContext context;
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





    @Test void normalClosurePolicyStillRefusesWhenEveryTrueAndNullTaskHasGenuineCompletionAndAdministrativeAcknowledgement()throws Exception{
        assertThat(context.getBeansOfType(OrderClosurePolicy.class)).hasSize(1);assertThat(context.containsBean("syntheticOrderClosurePolicy")).isFalse();assertThat(closurePolicy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);assertThat(managementPolicy).isSameAs(fixtureManagementPolicy);assertThat(managementPolicy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);
        Cohort flow=cohort(false);Map<String,String> before=allDigest();business(admin.write("POST",confirmationPath(flow.firstCompletion())+"/confirm",fields("expectedVersion",0,"confirmCompletion",true,"reason",MANAGEMENT_REASON),key()),"E2103","POLICY_NOT_CONFIRMED");assertThat(allDigest()).isEqualTo(before);
        // Explicit DB-gated fixture stage: build real facts through normal HTTP commands, never SQL counterfeit audit.
        fixtureManagementPolicy.mode=PolicyMode.SYNTHETIC_TEST_ONLY;confirm(flow.firstCompletion(),0,MANAGEMENT_REASON,key());confirm(flow.secondCompletion(),0,MANAGEMENT_REASON,key());fixtureManagementPolicy.mode=PolicyMode.UNCONFIRMED;
        JsonNode view=company(admin.get(closureBase(flow.source())+"/closure"),200,"0000");assertThat(view.get("counts").get("taskCount").asLong()).isEqualTo(2);assertThat(view.get("counts").get("completedTaskCount").asLong()).isEqualTo(2);assertThat(view.get("counts").get("confirmedTaskCount").asLong()).isEqualTo(2);assertThat(view.get("counts").get("effectiveOccupationCount").asLong()).isZero();assertThat(view.get("counts").get("blockingExceptionCount").asLong()).isZero();assertThat(view.get("policy").get("mode").asString()).isEqualTo("UNCONFIRMED");assertThat(view.get("policy").get("blocker").asString()).isEqualTo("POLICY_NOT_CONFIRMED");assertThat(view.get("policy").get("requirements").isNull()).isTrue();
        before=allDigest();String key=key();for(int attempt=0;attempt<2;attempt++)business(close(flow.source(),2,CLOSURE_REASON,key),"E2103","POLICY_NOT_CONFIRMED");assertThat(allDigest()).isEqualTo(before);assertThat(count("tom_order_closure")).isZero();assertThat(count("tom_order_closure_command")).isZero();assertThat(count("tms_completion_confirmation")).isEqualTo(2);assertThat(closurePolicy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);assertThat(managementPolicy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);
    }

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
