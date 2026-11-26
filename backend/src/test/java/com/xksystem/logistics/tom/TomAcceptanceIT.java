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
@Import(TomAcceptanceIT.TimeConfiguration.class)
class TomAcceptanceIT {
    private static final String CUSTOMER_A = "00000000-0000-4000-8000-000000000101";
    private static final String CUSTOMER_B = "00000000-0000-4000-8000-000000000102";
    private static final String CUSTOMER_C = "00000000-0000-4000-8000-000000000103";
    private static final List<String> TABLES = List.of("order_core", "tom_order", "cargo_item", "transport_task", "task_dependency",
            "order_draft_revision", "api_idempotency");

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
        @Bean @Primary MutableTestClock acceptanceClock() { return new MutableTestClock(); }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private Account actor;
    private Browser admin;

    @BeforeEach
    void isolateSyntheticAccountsCustomersAndOrders() throws Exception {
        jdbc.execute("TRUNCATE TABLE logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle, " + TABLES.stream().map(table -> "logistics." + table).collect(java.util.stream.Collectors.joining(","))
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

    @ParameterizedTest(name="explicit acceptance preserves the original {0} aggregate")
    @org.junit.jupiter.params.provider.ValueSource(strings={"IMPORT","EXPORT","LOCAL"})
    void allThreeOriginalCompleteGraphsAcceptExactlyOnceWithImmutableServerEventAndRevision(String type)throws Exception{
        ObjectNode draft=complete(type);JsonNode before=create(admin,draft,key());assertThat(check(admin,id(before)).get("pendingPolicyIds").size()).isPositive();String children=childrenDigest(UUID.fromString(id(before)));
        var reply=admin.order("POST",acceptPath(before),request(0),key(),true);legacy(reply,200,"OK","OrderEnvelope");assertThat(reply.headers().firstValue("Idempotency-Replayed")).contains("false");JsonNode accepted=data(reply);
        assertAcceptedOnlyChangesRoot(before,accepted,actor.id());assertThat(childrenDigest(UUID.fromString(id(before)))).isEqualTo(children);assertEvent(accepted,0,actor.id());assertThat(count("order_acceptance_event")).isEqualTo(1);assertThat(count("order_draft_revision")).isEqualTo(2);assertThat(count("api_idempotency")).isEqualTo(2);
    }

    @Test void acceptedGetListCheckAndNewPutKeepOriginalSchemasAndCheckRemainsPureNotGranted()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());JsonNode accepted=accept(admin,before,key());Map<String,Long> unchanged=counts();
        var get=admin.get("/orders/"+id(accepted));legacy(get,200,"OK","OrderEnvelope");assertThat(data(get)).isEqualTo(accepted);
        var list=admin.get("/orders");legacy(list,200,"OK","OrderPageEnvelope");assertThat(data(list).get("content").get(0).get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");
        JsonNode checked=check(admin,id(accepted));assertThat(checked.get("operationalAcceptance").asString()).isEqualTo("NOT_GRANTED");assertThat(checked.get("confirmedRulesOutcome").asString()).isEqualTo("COMPLETE");assertThat(checked.get("version")).isEqualTo(accepted.get("version"));
        legacy(admin.order("PUT","/orders/"+id(accepted),replacement(1,complete("LOCAL")),key(),true),409,"ORDER_NOT_EDITABLE","ErrorEnvelope");legacy(admin.get(acceptPath(accepted)),404,"ORDER_NOT_FOUND","ErrorEnvelope");assertThat(counts()).isEqualTo(unchanged);assertThat(data(admin.get("/orders/"+id(accepted)))).isEqualTo(accepted);
        assertThat(accepted.propertyNames()).doesNotContain("acceptedAt","acceptedBy","actorId","eventId","acceptanceEvent");
    }

    @Test void incompletenessCopiesTheOriginalMissingFieldsAndFailureDoesNotReserveItsKey()throws Exception{
        JsonNode before=create(admin,minimal(CUSTOMER_A),key());JsonNode checked=check(admin,id(before));Map<String,Long> unchanged=counts();String retryKey=key();
        var rejected=admin.order("POST",acceptPath(before),request(0),retryKey,true);legacy(rejected,400,"VALIDATION_ERROR","ErrorEnvelope");assertThat(body(rejected).get("fieldErrors")).isEqualTo(checked.get("missingFields"));assertThat(data(rejected).isNull()).isTrue();assertThat(counts()).isEqualTo(unchanged);
        ObjectNode complete=complete("LOCAL").put("customerId",CUSTOMER_A);JsonNode saved=replace(admin,id(before),0,complete,key());JsonNode accepted=accept(admin,saved,retryKey);assertEvent(accepted,1,actor.id());
    }

    @Test void earlierCompleteCheckCannotAuthorizeLaterIncompleteOrDisabledCustomerState()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());assertThat(check(admin,id(before)).get("confirmedRulesOutcome").asString()).isEqualTo("COMPLETE");
        JsonNode missing=replace(admin,id(before),0,minimal(CUSTOMER_C),key());legacy(admin.order("POST",acceptPath(missing),request(1),key(),true),400,"VALIDATION_ERROR","ErrorEnvelope");
        JsonNode complete=replace(admin,id(before),1,complete("LOCAL"),key());assertThat(check(admin,id(complete)).get("confirmedRulesOutcome").asString()).isEqualTo("COMPLETE");jdbc.update("UPDATE logistics.crm_customer SET enabled=false WHERE id=?",UUID.fromString(CUSTOMER_C));
        var invalid=admin.order("POST",acceptPath(complete),request(2),key(),true);legacy(invalid,400,"INVALID_CUSTOMER","ErrorEnvelope");assertCustomerIssue(invalid);assertThat(count("order_acceptance_event")).isZero();assertThat(data(admin.get("/orders/"+id(before)))).isEqualTo(complete);
    }

    @Test void acceptanceUsesNormalManagersAndTheUnchangedTlsSessionCsrfAndForcePasswordBoundaries()throws Exception{
        JsonNode initial=create(admin,complete("LOCAL"),key());Browser anonymous=new Browser();legacy(anonymous.order("POST",acceptPath(initial),request(0),key(),false),401,"UNAUTHENTICATED","ErrorEnvelope");
        legacy(admin.order("POST",acceptPath(initial),request(0),key(),false),403,"CSRF_INVALID","ErrorEnvelope");
        for(String role:List.of("ADMIN","DISPATCHER","DRIVER")){
            Browser member=new Browser();login(member,account(role,false));JsonNode own=create(admin,complete("LOCAL"),key());var reply=member.order("POST",acceptPath(own),request(0),key(),true);
            legacy(reply,role.equals("DRIVER")?403:200,role.equals("DRIVER")?"FORBIDDEN":"OK",role.equals("DRIVER")?"ErrorEnvelope":"OrderEnvelope");
            Browser forced=new Browser();login(forced,account(role,true));legacy(forced.order("POST",acceptPath(initial),request(0),key(),true),403,"FORBIDDEN","ErrorEnvelope");
        }
        assertThat(count("order_acceptance_event")).isEqualTo(2);
    }

    @Test void newPostSupportsTheRealHistoricalCookieAliasWithoutAddingAnAcceptGetOperation()throws Exception{
        AliasBrowser alias=new AliasBrowser();String token=alias.csrf();upms(alias.send("POST","/auth/login",json.writeValueAsString(Map.of("username",actor.username(),"password",actor.password())),token,null,null),200);token=alias.csrf();
        var created=alias.send("POST","/orders",complete("LOCAL").toString(),token,key(),null);legacy(created,201,"OK","OrderEnvelope");JsonNode before=data(created);
        var reply=alias.send("POST",acceptPath(before),request(0).toString(),token,key(),null);legacy(reply,200,"OK","OrderEnvelope");assertEvent(data(reply),0,actor.id());legacy(alias.send("GET",acceptPath(before),null,null,null,null),404,"ORDER_NOT_FOUND","ErrorEnvelope");
    }

    @Test void malformedInputHeaderMediaQueryUuidAndUtf8NeverWriteOrReturnAnotherModulesEnvelope()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());String path=acceptPath(before);Map<String,Long> unchanged=counts();
        for(String raw:List.of("{}","[]","null","{","{} {}","{\"expectedVersion\":0,\"expectedVersion\":1}","{\"expectedVersion\":null}","{\"expectedVersion\":\"0\"}","{\"expectedVersion\":0.1}","{\"expectedVersion\":-1}","{\"expectedVersion\":9007199254740992}","{\"expectedVersion\":1e10000000}","{\"expectedVersion\":1e-10000000}","{\"expectedVersion\":0,\"actorId\":\"synthetic\"}","{\"expectedVersion\":0,\"acceptedAt\":\"client\"}","{\"expectedVersion\":0,\"draft\":{}}","{\"expectedVersion\":\"\\ud800\"}"))legacy(admin.raw("POST",path,raw,key(),true),400,"VALIDATION_ERROR","ErrorEnvelope");
        for(String invalid:List.of("","bad key","x".repeat(129)))legacy(admin.order("POST",path,request(0),invalid,true),400,"VALIDATION_ERROR","ErrorEnvelope");legacy(admin.order("POST",path,request(0),null,true),400,"VALIDATION_ERROR","ErrorEnvelope");
        legacy(admin.order("POST",path+"?unknown=1",request(0),key(),true),400,"VALIDATION_ERROR","ErrorEnvelope");legacy(admin.order("POST","/orders/1-1-1-1-1/accept",request(0),key(),true),400,"VALIDATION_ERROR","ErrorEnvelope");legacy(admin.order("POST","/orders/"+UUID.randomUUID()+"/accept",request(0),key(),true),404,"ORDER_NOT_FOUND","ErrorEnvelope");
        String token=admin.csrf();var malformed=custom(admin,"POST",path,new byte[]{(byte)0xc3,0x28},"application/json",List.of(key()),token);legacy(malformed,400,"VALIDATION_ERROR","ErrorEnvelope");
        legacy(custom(admin,"POST",path,request(0).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/plain",List.of(key()),token),400,"VALIDATION_ERROR","ErrorEnvelope");legacy(custom(admin,"POST",path,request(0).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/json",List.of(key(),key()),token),400,"VALIDATION_ERROR","ErrorEnvelope");
        assertThat(counts()).isEqualTo(unchanged);assertThat(data(admin.get("/orders/"+id(before)))).isEqualTo(before);
    }

    @Test void acceptPreservesNulRawDecimalsOffsetsNullObjectsChildIdentityAndHistoricalCustomerName()throws Exception{
        ObjectNode draft=complete("LOCAL");draft.put("remark","合成\u0000備註 e\u0301 ");draft.put("customerReference"," \u0000 ");ObjectNode item=(ObjectNode)draft.get("items").get(0);item.put("grossWeight","0.0000000000000000001");item.put("cbm","0.000");
        ObjectNode task=(ObjectNode)draft.get("tasks").get(0);task.put("pickupAddress","合成\u0000地址");task.set("siteContact",json.createObjectNode());String wire="1500-01-02T00:00:00.123456+23:59";task.put("plannedStartAt",wire);task.put("plannedEndAt","1500-01-02T01:00:00.123456+23:59");
        JsonNode before=create(admin,draft,key());String childDigest=childrenDigest(UUID.fromString(id(before)));jdbc.update("UPDATE logistics.crm_customer SET name='合成已改名主檔',version=version+1 WHERE id=?",UUID.fromString(CUSTOMER_C));
        JsonNode accepted=accept(admin,before,key());assertAcceptedOnlyChangesRoot(before,accepted,actor.id());assertThat(childrenDigest(UUID.fromString(id(before)))).isEqualTo(childDigest);assertEvent(accepted,0,actor.id());
        assertThat(accepted.get("remark").asString()).contains("\u0000");assertThat(accepted.get("customerName")).isEqualTo(before.get("customerName"));assertThat(accepted.get("tasks").get(0).get("siteContact").isObject()).isTrue();
        var instant=com.xksystem.logistics.tom.domain.DraftCodec.parseInstant(wire);var expected=java.math.BigDecimal.valueOf(instant.getEpochSecond()).add(java.math.BigDecimal.valueOf(instant.getNano(),9));assertThat(jdbc.queryForObject("SELECT extract(epoch FROM planned_start_at) FROM logistics.transport_task WHERE order_id=? ORDER BY input_position LIMIT 1",java.math.BigDecimal.class,UUID.fromString(id(before)))).isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema='logistics' AND table_name='order_acceptance_event' AND column_name='snapshot'",String.class)).isEqualTo("json");
    }

    @Test void serverClockIsTruncatedToMicrosAndEventTimeMatchesTheOnlyNewRootVersion()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());clock.advance(Duration.ofNanos(987654321));JsonNode accepted=accept(admin,before,key());Instant expected=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThat(Instant.parse(accepted.get("updatedAt").asString())).isEqualTo(expected);assertThat(jdbc.queryForObject("SELECT accepted_at FROM logistics.order_acceptance_event WHERE order_id=?",java.time.OffsetDateTime.class,UUID.fromString(id(before))).toInstant()).isEqualTo(expected);assertEvent(accepted,0,actor.id());
    }

    @Test void safeMaximumVersionAndNonDraftOrNonOpenStatesRejectWithoutMutation()throws Exception{
        for(String state:List.of("CANCELLED","CLOSED","ACCEPTED","MAX")){
            JsonNode before=create(admin,complete("LOCAL"),key());UUID id=UUID.fromString(id(before));
            if(state.equals("MAX"))jdbc.update("UPDATE logistics.tom_order SET version=9007199254740991 WHERE order_id=?",id);else if(state.equals("ACCEPTED"))jdbc.update("UPDATE logistics.tom_order SET acceptance_status='ACCEPTED' WHERE order_id=?",id);else jdbc.update("UPDATE logistics.order_core SET lifecycle_status=? WHERE id=?",state,id);
            JsonNode persisted=data(admin.get("/orders/"+id));Map<String,Long> unchanged=counts();legacy(admin.order("POST","/orders/"+id+"/accept",request(state.equals("MAX")?9007199254740991L:0),key(),true),409,"ORDER_NOT_EDITABLE","ErrorEnvelope");assertThat(counts()).isEqualTo(unchanged);assertThat(data(admin.get("/orders/"+id))).isEqualTo(persisted);
        }
    }

    @Test void oldCreateAndPutSuccessfulReplayRemainHistoricalDraftAfterAcceptanceAndCustomerDisable()throws Exception{
        ObjectNode draft=complete("LOCAL");String shared=key();var posted=admin.order("POST","/orders",draft,shared,true);legacy(posted,201,"OK","OrderEnvelope");JsonNode v0=data(posted);String putKey=key();ObjectNode changed=draft.deepCopy().put("remark","合成草稿版本一");var saved=admin.order("PUT","/orders/"+id(v0),replacement(0,changed),putKey,true);legacy(saved,200,"OK","OrderEnvelope");JsonNode v1=data(saved);
        var accepted=admin.order("POST",acceptPath(v1),request(1),shared,true);legacy(accepted,200,"OK","OrderEnvelope");JsonNode v2=data(accepted);jdbc.update("UPDATE logistics.crm_customer SET enabled=false WHERE id=?",UUID.fromString(CUSTOMER_C));Map<String,Long> unchanged=counts();
        var postReplay=admin.order("POST","/orders",draft,shared,true);legacy(postReplay,201,"OK","OrderEnvelope");assertThat(data(postReplay)).isEqualTo(v0);assertThat(postReplay.headers().firstValue("Location")).isEqualTo(posted.headers().firstValue("Location"));
        var putReplay=admin.order("PUT","/orders/"+id(v0),replacement(0,changed),putKey,true);legacy(putReplay,200,"OK","OrderEnvelope");assertThat(data(putReplay)).isEqualTo(v1);assertThat(data(putReplay).get("acceptanceStatus").asString()).isEqualTo("DRAFT");
        var acceptReplay=admin.order("POST",acceptPath(v1),request(1),shared,true);legacy(acceptReplay,200,"OK","OrderEnvelope");assertThat(data(acceptReplay)).isEqualTo(v2);
        for(var replay:List.of(postReplay,putReplay,acceptReplay))assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(body(acceptReplay).get("traceId")).isNotEqualTo(body(accepted).get("traceId"));assertThat(counts()).isEqualTo(unchanged);assertThat(data(admin.get("/orders/"+id(v0)))).isEqualTo(v2);
    }

    @Test void acceptHashUsesOriginalMathematicalIntegerCanonicalizationAndKeyScopesWithoutRecheckingCurrentState()throws Exception{
        JsonNode first=create(admin,complete("LOCAL"),key());String key=key();var accepted=admin.raw("POST",acceptPath(first),"{\"expectedVersion\":-0.0}",key,true);legacy(accepted,200,"OK","OrderEnvelope");
        var replay=admin.raw("POST",acceptPath(first).replace(id(first),id(first).toUpperCase(java.util.Locale.ROOT)),"{ \"expectedVersion\" : 0e0 }",key,true);legacy(replay,200,"OK","OrderEnvelope");assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(data(replay)).isEqualTo(data(accepted));
        jdbc.update("UPDATE logistics.crm_customer SET enabled=false WHERE id=?",UUID.fromString(CUSTOMER_C));legacy(admin.order("POST",acceptPath(first),request(1),key,true),409,"IDEMPOTENCY_KEY_REUSED","ErrorEnvelope");legacy(admin.raw("POST",acceptPath(first),"{\"expectedVersion\":null}",key,true),400,"VALIDATION_ERROR","ErrorEnvelope");
        jdbc.update("UPDATE logistics.crm_customer SET enabled=true WHERE id=?",UUID.fromString(CUSTOMER_C));JsonNode second=create(admin,complete("LOCAL"),key());accept(admin,second,key);
        Browser dispatcher=new Browser();login(dispatcher,account("DISPATCHER",false));legacy(dispatcher.order("POST",acceptPath(first),request(1),key,true),409,"ORDER_NOT_EDITABLE","ErrorEnvelope");assertThat(count("order_acceptance_event")).isEqualTo(2);
    }

    @Test void wrongExpectedVersionReportsActualVersionAndConfirmedFieldCoordinatesWithoutMutation()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());JsonNode updated=replace(admin,id(before),0,complete("LOCAL"),key());Map<String,Long> unchanged=counts();var rejected=admin.order("POST",acceptPath(before),request(0),key(),true);legacy(rejected,409,"VERSION_CONFLICT","ErrorEnvelope");
        assertThat(data(rejected).get("actualVersion").asLong()).isEqualTo(1);JsonNode issue=body(rejected).get("fieldErrors").get(0);assertThat(issue.get("path").asString()).isEqualTo("/expectedVersion");assertThat(issue.get("fieldId").asString()).isEqualTo("AF01");assertThat(issue.get("ruleId").asString()).isEqualTo("VERSION");assertThat(counts()).isEqualTo(unchanged);assertThat(data(admin.get("/orders/"+id(before)))).isEqualTo(updated);
    }

    @Test void simultaneousSameKeyReturnsImmediateInProgressThenReplaysOneEventAfterCommit()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());String key=key(),token=admin.csrf();pause("order_acceptance_event","BEFORE INSERT");var pool=Executors.newFixedThreadPool(2);
        try(var gate=dataSource.getConnection()){advisory(gate,true);try{
            var first=pool.submit(()->admin.withToken("POST",acceptPath(before),request(0).toString(),key,token));awaitLock("order_acceptance_event");
            long started=System.nanoTime();var processing=admin.withToken("POST",acceptPath(before),request(0).toString(),key,token);legacy(processing,409,"IDEMPOTENCY_IN_PROGRESS","ErrorEnvelope");assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(5));advisory(gate,false);
            var accepted=first.get(15,TimeUnit.SECONDS);legacy(accepted,200,"OK","OrderEnvelope");var replay=admin.order("POST",acceptPath(before),request(0),key,true);legacy(replay,200,"OK","OrderEnvelope");assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(data(replay)).isEqualTo(data(accepted));assertThat(count("order_acceptance_event")).isEqualTo(1);
        }finally{advisory(gate,false);}}finally{unpause("order_acceptance_event");pool.shutdownNow();}
    }

    @Test void differentKeysAndActorsSerializeAndCannotAcceptOneOrderTwice()throws Exception{
        Browser dispatcher=new Browser();login(dispatcher,account("DISPATCHER",false));
        for(Browser second:List.of(admin,dispatcher)){
            JsonNode before=create(admin,complete("LOCAL"),key());String firstToken=admin.csrf(),secondToken=second.csrf();pause("order_acceptance_event","BEFORE INSERT");var pool=Executors.newFixedThreadPool(2);
            try(var gate=dataSource.getConnection()){advisory(gate,true);try{
                var first=pool.submit(()->admin.withToken("POST",acceptPath(before),request(0).toString(),key(),firstToken));awaitLock("order_acceptance_event");
                var later=pool.submit(()->second.withToken("POST",acceptPath(before),request(1).toString(),key(),secondToken));awaitLock("upms_lock");assertThat(later.isDone()).isFalse();advisory(gate,false);legacy(first.get(15,TimeUnit.SECONDS),200,"OK","OrderEnvelope");legacy(later.get(15,TimeUnit.SECONDS),409,"ORDER_NOT_EDITABLE","ErrorEnvelope");assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.order_acceptance_event WHERE order_id=?",Long.class,UUID.fromString(id(before)))).isEqualTo(1);
            }finally{advisory(gate,false);}}finally{unpause("order_acceptance_event");pool.shutdownNow();}
        }
    }

    @Test void acceptanceWinningTheTransactionRejectsALaterNewDraftPutInsteadOfRewritingAcceptedData()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());String token=admin.csrf();pause("order_acceptance_event","BEFORE INSERT");var pool=Executors.newFixedThreadPool(2);
        try(var gate=dataSource.getConnection()){advisory(gate,true);try{
            var first=pool.submit(()->admin.withToken("POST",acceptPath(before),request(0).toString(),key(),token));awaitLock("order_acceptance_event");
            var saved=pool.submit(()->admin.withToken("PUT","/orders/"+id(before),replacement(0,complete("LOCAL").put("remark","不應寫入")).toString(),key(),token));awaitLock("upms_lock");advisory(gate,false);var accepted=first.get(15,TimeUnit.SECONDS);legacy(accepted,200,"OK","OrderEnvelope");legacy(saved.get(15,TimeUnit.SECONDS),409,"VERSION_CONFLICT","ErrorEnvelope");assertThat(data(admin.get("/orders/"+id(before)))).isEqualTo(data(accepted));assertThat(count("order_acceptance_event")).isEqualTo(1);
        }finally{advisory(gate,false);}}finally{unpause("order_acceptance_event");pool.shutdownNow();}
    }

    @Test void draftPutWinningTheTransactionMakesTheOlderAcceptVersionConflictAndARefreshCanThenAccept()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());String token=admin.csrf();pause("tom_order","BEFORE UPDATE");var pool=Executors.newFixedThreadPool(2);
        try(var gate=dataSource.getConnection()){advisory(gate,true);try{
            var saved=pool.submit(()->admin.withToken("PUT","/orders/"+id(before),replacement(0,complete("LOCAL").put("remark","合成先保存")).toString(),key(),token));awaitLock("tom_order");
            var pending=pool.submit(()->admin.withToken("POST",acceptPath(before),request(0).toString(),key(),token));awaitLock("upms_lock");advisory(gate,false);var stored=saved.get(15,TimeUnit.SECONDS);legacy(stored,200,"OK","OrderEnvelope");legacy(pending.get(15,TimeUnit.SECONDS),409,"VERSION_CONFLICT","ErrorEnvelope");assertThat(count("order_acceptance_event")).isZero();
        }finally{advisory(gate,false);}}finally{unpause("tom_order");pool.shutdownNow();}
        JsonNode accepted=accept(admin,data(admin.get("/orders/"+id(before))),key());assertThat(accepted.get("remark").asString()).isEqualTo("合成先保存");assertEvent(accepted,1,actor.id());
    }

    @Test void customerDisableWinningItsSourceLockRejectsAcceptanceWithoutTrustingAnEarlierCheck()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());String token=admin.csrf();assertThat(check(admin,id(before)).get("confirmedRulesOutcome").asString()).isEqualTo("COMPLETE");Map<String,Long> unchanged=counts();
        try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
            held.setAutoCommit(false);try(var statement=held.prepareStatement("UPDATE logistics.crm_customer SET enabled=false,version=version+1 WHERE id=?")){statement.setObject(1,UUID.fromString(CUSTOMER_C));statement.executeUpdate();}
            var pending=pool.submit(()->admin.withToken("POST",acceptPath(before),request(0).toString(),key(),token));awaitLock("crm_customer");held.commit();var rejected=pending.get(15,TimeUnit.SECONDS);legacy(rejected,400,"INVALID_CUSTOMER","ErrorEnvelope");assertCustomerIssue(rejected);
        }
        assertThat(counts()).isEqualTo(unchanged);assertThat(data(admin.get("/orders/"+id(before)))).isEqualTo(before);
    }

    @Test void acceptanceHoldsCustomerEligibilityThroughCommitAndKeepsTheOriginalHistoricalNameAfterRename()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());jdbc.update("UPDATE logistics.crm_customer SET name='合成受理前已改名',version=version+1 WHERE id=?",UUID.fromString(CUSTOMER_C));String token=admin.csrf();pause("order_acceptance_event","BEFORE INSERT");var pool=Executors.newFixedThreadPool(2);
        try(var gate=dataSource.getConnection()){advisory(gate,true);try{
            var accepted=pool.submit(()->admin.withToken("POST",acceptPath(before),request(0).toString(),key(),token));awaitLock("order_acceptance_event");var disabling=pool.submit(()->jdbc.update("UPDATE logistics.crm_customer SET enabled=false,name='合成受理後再次改名',version=version+1 WHERE id=?",UUID.fromString(CUSTOMER_C)));awaitLock("crm_customer");assertThat(disabling.isDone()).isFalse();advisory(gate,false);
            var reply=accepted.get(15,TimeUnit.SECONDS);legacy(reply,200,"OK","OrderEnvelope");assertThat(disabling.get(15,TimeUnit.SECONDS)).isEqualTo(1);assertThat(data(reply).get("customerName")).isEqualTo(before.get("customerName"));assertEvent(data(reply),0,actor.id());assertThat(check(admin,id(before)).get("confirmedRulesOutcome").asString()).isEqualTo("INCOMPLETE");
        }finally{advisory(gate,false);}}finally{unpause("order_acceptance_event");pool.shutdownNow();}
    }

    @Test void trustedExternalCustomerChangeBetweenObservationAndRootLockFailsClosed()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());String token=admin.csrf();try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
            held.setAutoCommit(false);try(var statement=held.prepareStatement("SELECT id FROM logistics.crm_customer WHERE id=? FOR UPDATE")){statement.setObject(1,UUID.fromString(CUSTOMER_C));statement.executeQuery().close();}
            var pending=pool.submit(()->admin.withToken("POST",acceptPath(before),request(0).toString(),key(),token));awaitLock("crm_customer");jdbc.update("UPDATE logistics.tom_order SET customer_id=? WHERE order_id=?",UUID.fromString(CUSTOMER_B),UUID.fromString(id(before)));held.commit();legacy(pending.get(15,TimeUnit.SECONDS),409,"ORDER_NOT_EDITABLE","ErrorEnvelope");
        }
        assertThat(count("order_acceptance_event")).isZero();assertThat(jdbc.queryForObject("SELECT version FROM logistics.tom_order WHERE order_id=?",Long.class,UUID.fromString(id(before)))).isZero();
    }

    @Test void bothNewAcceptAndSuccessfulReplayRefreshActorAuthorizationAfterWaitingForCanonicalLock()throws Exception{
        for(boolean replay:List.of(false,true)){
            JsonNode before=create(admin,complete("LOCAL"),key());Account manager=account("ADMIN",false);Browser member=new Browser();login(member,manager);String key=key();if(replay)accept(member,before,key);Map<String,Long> unchanged=counts();String token=member.csrf();
            try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
                held.setAutoCommit(false);try(var statement=held.createStatement()){statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();}
                var pending=pool.submit(()->member.withToken("POST",acceptPath(before),request(0).toString(),key,token));awaitLock("upms_lock");try(var statement=held.prepareStatement("UPDATE logistics.upms_account SET role='DRIVER',auth_version=auth_version+1 WHERE id=?")){statement.setObject(1,manager.id());statement.executeUpdate();}held.commit();legacy(pending.get(15,TimeUnit.SECONDS),401,"UNAUTHENTICATED","ErrorEnvelope");
            }
            assertThat(counts()).isEqualTo(unchanged);upms(member.get("/auth/csrf"),200);
        }
    }

    @Test void eventRevisionAndReplayInsertionFailuresRollBackEveryAcceptanceMutationAndPermitSameKeyRetry()throws Exception{
        for(String table:List.of("order_draft_revision","order_acceptance_event","api_idempotency")){
            JsonNode before=create(admin,complete("LOCAL"),key());Map<String,Long> unchanged=counts();String key=key(),digest=childrenDigest(UUID.fromString(id(before)));
            String condition=table.equals("order_draft_revision")?" WHEN (NEW.aggregate_version>0)":table.equals("api_idempotency")?" WHEN (NEW.canonical_path LIKE '%/accept')":"";
            jdbc.execute("CREATE FUNCTION logistics.test_fail_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic acceptance storage failure'; END $$");jdbc.execute("CREATE TRIGGER test_fail_acceptance BEFORE INSERT ON logistics."+table+" FOR EACH ROW"+condition+" EXECUTE FUNCTION logistics.test_fail_acceptance()");
            try{var failed=admin.order("POST",acceptPath(before),request(0),key,true);legacy(failed,500,"INTERNAL_ERROR","ErrorEnvelope");assertThat(failed.body()).doesNotContain("synthetic acceptance storage failure","INSERT INTO","SQLException","Hibernate");}
            finally{jdbc.execute("DROP TRIGGER test_fail_acceptance ON logistics."+table);jdbc.execute("DROP FUNCTION logistics.test_fail_acceptance()");}
            assertThat(counts()).isEqualTo(unchanged);assertThat(data(admin.get("/orders/"+id(before)))).isEqualTo(before);assertThat(childrenDigest(UUID.fromString(id(before)))).isEqualTo(digest);JsonNode accepted=accept(admin,before,key);assertEvent(accepted,0,actor.id());
        }
    }

    @Test void aRealEventForeignKeyFailureUsesProtectedRelationAndRollsBackRootRevisionAndReplay()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());Map<String,Long> unchanged=counts();String key=key();
        jdbc.execute("CREATE FUNCTION logistics.test_invalid_event_actor() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.actor_id='00000000-0000-4000-8000-000000009999'; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_invalid_event_actor BEFORE INSERT ON logistics.order_acceptance_event FOR EACH ROW EXECUTE FUNCTION logistics.test_invalid_event_actor()");
        try{legacy(admin.order("POST",acceptPath(before),request(0),key,true),409,"PROTECTED_RELATION","ErrorEnvelope");}
        finally{jdbc.execute("DROP TRIGGER test_invalid_event_actor ON logistics.order_acceptance_event");jdbc.execute("DROP FUNCTION logistics.test_invalid_event_actor()");}
        assertThat(counts()).isEqualTo(unchanged);assertThat(data(admin.get("/orders/"+id(before)))).isEqualTo(before);accept(admin,before,key);
    }

    @Test void acceptanceEventAndDraftRevisionCannotBeChangedOrDeletedAndDatabaseRejectsDuplicateEvents()throws Exception{
        JsonNode before=create(admin,complete("LOCAL"),key());JsonNode accepted=accept(admin,before,key());UUID id=UUID.fromString(id(before));String original=jdbc.queryForObject("SELECT md5(row_to_json(e)::text) FROM logistics.order_acceptance_event e WHERE order_id=?",String.class,id);
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.order_acceptance_event SET request_trace_id='changed' WHERE order_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM logistics.order_acceptance_event WHERE order_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.order_draft_revision SET request_trace_id='changed' WHERE order_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM logistics.order_draft_revision WHERE order_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.order_acceptance_event(id,order_id,from_version,to_version,actor_id,accepted_at,snapshot,request_trace_id) SELECT ?,order_id,from_version,to_version,actor_id,accepted_at,snapshot,request_trace_id FROM logistics.order_acceptance_event WHERE order_id=?",UUID.randomUUID(),id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT md5(row_to_json(e)::text) FROM logistics.order_acceptance_event e WHERE order_id=?",String.class,id)).isEqualTo(original);assertEvent(accepted,0,actor.id());assertThat(count("order_acceptance_event")).isEqualTo(1);
    }

    private ObjectNode complete(String type){return (ObjectNode)TomContract.example("graphCases","DEMO-"+type+"-CONTRACT").get("draft").deepCopy();}
    private ObjectNode request(long version){return json.createObjectNode().put("expectedVersion",version);}
    private String acceptPath(JsonNode order){return "/orders/"+id(order)+"/accept";}
    private JsonNode accept(Browser browser,JsonNode before,String key)throws Exception{var reply=browser.order("POST",acceptPath(before),request(before.get("version").asLong()),key,true);legacy(reply,200,"OK","OrderEnvelope");assertThat(reply.headers().firstValue("Idempotency-Replayed")).contains("false");JsonNode result=data(reply);assertThat(result.get("acceptanceStatus").asString()).isEqualTo("ACCEPTED");assertThat(result.get("lifecycleStatus").asString()).isEqualTo("OPEN");assertThat(result.get("version").asLong()).isEqualTo(before.get("version").asLong()+1);return result;}
    private void assertAcceptedOnlyChangesRoot(JsonNode before,JsonNode accepted,UUID actor){ObjectNode expected=(ObjectNode)before.deepCopy();expected.put("acceptanceStatus","ACCEPTED");expected.put("version",before.get("version").asLong()+1);expected.set("updatedAt",accepted.get("updatedAt"));expected.put("updatedBy",actor.toString());assertThat(accepted).isEqualTo(json.readTree(expected.toString()));}
    private void assertEvent(JsonNode accepted,long from,UUID actor){UUID id=UUID.fromString(id(accepted));var event=jdbc.queryForMap("SELECT from_version,to_version,actor_id,accepted_at,snapshot::text,request_trace_id FROM logistics.order_acceptance_event WHERE order_id=?",id);assertThat(((Number)event.get("from_version")).longValue()).isEqualTo(from);assertThat(((Number)event.get("to_version")).longValue()).isEqualTo(from+1);assertThat(event.get("actor_id")).isEqualTo(actor);assertThat(json.readTree((String)event.get("snapshot"))).isEqualTo(accepted);assertThat(jdbc.queryForObject("SELECT accepted_at=updated_at FROM logistics.order_acceptance_event e JOIN logistics.tom_order t ON t.order_id=e.order_id WHERE e.order_id=?",Boolean.class,id)).isTrue();assertThat((String)event.get("request_trace_id")).isNotBlank();assertThat(json.readTree(jdbc.queryForObject("SELECT snapshot::text FROM logistics.order_draft_revision WHERE order_id=? AND aggregate_version=?",String.class,id,from+1))).isEqualTo(accepted);}
    private void assertCustomerIssue(HttpResponse<String> reply){assertThat(body(reply).get("fieldErrors")).anySatisfy(issue->{assertThat(issue.get("path").asString()).isEqualTo("/customerId");assertThat(issue.get("fieldId").asString()).isEqualTo("OF04");assertThat(issue.get("ruleId").asString()).isEqualTo("ORD-P01");});}
    private String childrenDigest(UUID order){StringBuilder result=new StringBuilder();for(String table:List.of("cargo_item","transport_task","task_dependency"))result.append(jdbc.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+(table.equals("task_dependency")?"task_id,predecessor_task_id":"id")+")::text,'[]')) FROM logistics."+table+" t WHERE order_id=?",String.class,order));return result.toString();}
    private void pause(String table,String event){jdbc.execute("CREATE FUNCTION logistics.test_pause_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202603,3); RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_pause_acceptance "+event+" ON logistics."+table+" FOR EACH ROW EXECUTE FUNCTION logistics.test_pause_acceptance()");}
    private void unpause(String table){jdbc.execute("DROP TRIGGER test_pause_acceptance ON logistics."+table);jdbc.execute("DROP FUNCTION logistics.test_pause_acceptance()");}
    private void advisory(java.sql.Connection connection,boolean acquire)throws java.sql.SQLException{try(var statement=connection.createStatement()){statement.execute("SELECT pg_advisory_"+(acquire?"lock":"unlock")+"(202603,3)");}}
    private void awaitLock(String table)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);try(var observer=dataSource.getConnection();var statement=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")){assertThat(observer.getAutoCommit()).isTrue();statement.setString(1,"%"+table+"%");while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getLong(1)>0)return;}Thread.sleep(20);}}throw new AssertionError("Expected a real acceptance database lock for "+table);}
    private HttpResponse<String> custom(Browser browser,String method,String path,byte[] bytes,String contentType,List<String> keys,String token)throws Exception{var request=HttpRequest.newBuilder(URI.create("https://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30)).header("Accept","application/json").header("Content-Type",contentType).header("X-CSRF-TOKEN",token);for(String key:keys)request.header("Idempotency-Key",key);return browser.client.send(request.method(method,HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());}
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
    private Map<String, Long> counts() { Map<String, Long> counts = new LinkedHashMap<>(); TABLES.forEach(table -> counts.put(table, count(table))); counts.put("order_acceptance_event",count("order_acceptance_event")); return counts; }
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
