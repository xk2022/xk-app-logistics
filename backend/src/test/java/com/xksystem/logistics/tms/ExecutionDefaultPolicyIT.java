package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.assertThat;

import com.xksystem.logistics.LogisticsApplication;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** The normal production policy stays closed even for a valid historical current assignment. */
@Testcontainers
@SpringBootTest(classes=LogisticsApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecutionDefaultPolicyIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres")).withStartupAttempts(3).withDatabaseName("xk_logistics_e2e");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);TlsTestSupport.register(registry);
    }
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired DispatchEligibilityPolicy policy;
    @Autowired TaskQueries sources;
    private final JsonMapper json=JsonMapper.builder().build();

    @ParameterizedTest @ValueSource(strings={"SYNTHETIC_TEST_ONLY","UNCONFIRMED"})
    void ordinaryStartupAllowsOwnedDetailButRefusesNewDepartureRegardlessOfRecordedAssignmentMode(String recordedMode)throws Exception{
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("xk_logistics_e2e");
        assertThat(context.getBeansOfType(DispatchEligibilityPolicy.class)).hasSize(1);
        assertThat(policy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);
        assertThat(context.containsBean("syntheticDispatchEligibilityPolicy")).isFalse();
        assertThat(context.getBeansWithAnnotation(org.springframework.boot.test.context.TestConfiguration.class)).isEmpty();
        assertThat(getClass().getClassLoader().getResource("com/xksystem/logistics/support/SyntheticDispatchPolicyConfiguration.class")).isNotNull();
        Account administrator=account("ADMIN"),worker=account("DRIVER");
        try(var admin=new Browser();var driver=new Browser()){
            login(admin,administrator);login(driver,worker);
            String suffix=UUID.randomUUID().toString().replace("-", "").substring(0,10).toUpperCase(java.util.Locale.ROOT);
            UUID category=null;for(JsonNode value:company(admin.get("/adm/categories?size=100"),200,"0000").get("content"))
                if(value.get("code").asString().equals("VEHICLE_TYPE"))category=uuid(value);
            assertThat(category).isNotNull();
            JsonNode type=company(admin.post("/adm/categories/"+category+"/items",fields("code","SYNTHETIC_EXEC_"+suffix,"label","合成正式政策拒絕車種","enabled",true,"sortOrder",1),null),201,"0000");
            JsonNode vehicle=company(admin.post("/fms/vehicles",fields("plateNo","SYN-EXEC-"+suffix,"vehicleType",id(type),"capacityTon","12.3400"),null),201,"0000");
            JsonNode master=company(admin.post("/fms/drivers",fields("driverCode","SYNTHETIC_EXEC_D_"+suffix,"name","合成正常政策司機","dutyStatus","ON_DUTY","accountId",worker.id().toString()),null),201,"0000");
            JsonNode customer=company(admin.post("/crm/customers",fields("customerCode","SYN-EXEC-"+suffix,"name","合成正常政策客戶"),null),201,"0000");
            ObjectNode draft=(ObjectNode)TomContract.example("graphCases","DEMO-LOCAL-CONTRACT").get("draft").deepCopy();draft.put("customerId",id(customer));
            for(JsonNode task:draft.get("tasks"))((ObjectNode)task).put("taskKind","SYNTHETIC_TMS_IT_"+task.get("clientRef").asString());
            JsonNode created=tom(admin.post("/orders",draft,key()),201);
            JsonNode accepted=tom(admin.post("/orders/"+id(created)+"/accept",fields("expectedVersion",0),key()),200);
            JsonNode first=null;for(JsonNode task:accepted.get("tasks"))if(task.get("predecessorRefs").isEmpty())first=task;
            assertThat(first).isNotNull();UUID taskId=uuid(first),assignment=UUID.randomUUID();
            // Explicit test-only historical arrangement. No production bootstrap, provider or policy is enabled.
            Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var driverSnapshot=fields("id",id(master),"driverCode",master.get("driverCode").asString(),"name",master.get("name").asString(),"version",0);
            var vehicleSnapshot=fields("id",id(vehicle),"plateNo",vehicle.get("plateNo").asString(),"vehicleType",id(type),"vehicleTypeCode",type.get("code").asString(),"vehicleTypeLabel",type.get("label").asString(),"version",0);
            TaskQueries.Source source=sources.find(taskId).orElseThrow().source();
            jdbc.update("INSERT INTO logistics.tms_assignment(id,task_id,order_id,status,version,assigned_generation,driver_id,main_vehicle_id,driver_snapshot,main_vehicle_snapshot,source_snapshot,policy_mode,assigned_by,assigned_at) VALUES (?,?,?,'ASSIGNED',0,1,?,?,?::json,?::json,?::json,?,?,?)",
                    assignment,taskId,uuid(accepted),uuid(master),uuid(vehicle),json.writeValueAsString(driverSnapshot),json.writeValueAsString(vehicleSnapshot),json.writeValueAsString(source),recordedMode,administrator.id(),OffsetDateTime.ofInstant(at,ZoneOffset.UTC));
            UUID driverReservation=UUID.randomUUID(),vehicleReservation=UUID.randomUUID(),eventId=UUID.randomUUID();
            jdbc.update("INSERT INTO logistics.tms_resource_reservation(id,assignment_id,driver_id,position,acquired_at) VALUES (?,?,?,'DRIVER',?)",driverReservation,assignment,uuid(master),OffsetDateTime.ofInstant(at,ZoneOffset.UTC));
            jdbc.update("INSERT INTO logistics.tms_resource_reservation(id,assignment_id,vehicle_id,position,acquired_at) VALUES (?,?,?,'MAIN',?)",vehicleReservation,assignment,uuid(vehicle),OffsetDateTime.ofInstant(at,ZoneOffset.UTC));
            jdbc.update("UPDATE logistics.tms_task_dispatch SET generation=1 WHERE task_id=?",taskId);
            var event=fields("id",eventId,"action","ASSIGNED","assignmentId",assignment,"previousAssignmentId",null,
                    "fromGeneration",0,"toGeneration",1,"actorId",administrator.id(),"occurredAt",at,"reason",null);
            var assignmentSnapshot=fields("id",assignment,"taskId",taskId,"orderId",uuid(accepted),"status","ASSIGNED","version",0,
                    "assignedGeneration",1,"endedGeneration",null,"driver",driverSnapshot,"mainVehicle",vehicleSnapshot,"trailer",null,
                    "source",source,"policyMode",recordedMode,"assignedBy",administrator.id(),"assignedAt",at,"assignmentReason",null,
                    "endedBy",null,"endedAt",null,"endReason",null,"previousAssignmentId",null,
                    "reservations",List.of(fields("id",driverReservation,"kind","DRIVER","position","DRIVER","resourceId",uuid(master),
                            "acquiredAt",at,"releasedAt",null,"releaseReason",null),fields("id",vehicleReservation,"kind","VEHICLE",
                            "position","MAIN","resourceId",uuid(vehicle),"acquiredAt",at,"releasedAt",null,"releaseReason",null)),"events",List.of(event));
            jdbc.update("INSERT INTO logistics.tms_dispatch_event(id,task_id,action,assignment_id,from_generation,to_generation,actor_id,occurred_at,snapshot) VALUES (?,?,'ASSIGNED',?,0,1,?,?,?::json)",
                    eventId,taskId,assignment,administrator.id(),OffsetDateTime.ofInstant(at,ZoneOffset.UTC),json.writeValueAsString(fields("taskId",taskId,"generation",1,"assignment",assignmentSnapshot)));
            Map<String,String> before=digest();String path="/tms/my-tasks/"+assignment;
            JsonNode detail=company(driver.get(path),200,"0000");
            assertThat(detail.propertyNames()).hasSize(15);assertThat(detail.get("assignmentId").asString()).isEqualTo(assignment.toString());
            assertThat(detail.get("policyMode").asString()).isEqualTo(recordedMode);
            assertThat(detail.get("execution").get("phase").asString()).isEqualTo("WAITING");
            assertThat(detail.get("execution").get("version").asLong()).isZero();assertThat(detail.get("execution").get("departedAt").isNull()).isTrue();
            String commandKey=key();
            for(int attempt=0;attempt<2;attempt++){
                JsonNode refusal=company(driver.post(path+"/depart",fields("expectedVersion",0),commandKey),409,"E1703");
                assertThat(refusal.propertyNames()).containsExactly("reason");assertThat(refusal.get("reason").asString()).isEqualTo("POLICY_NOT_CONFIRMED");
                for(String table:List.of("tms_execution","tms_execution_event","tms_execution_command"))assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics."+table,Long.class)).isZero();
                assertThat(digest()).isEqualTo(before);
            }
            assertThat(policy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);
        }
    }
    private Map<String,String> digest(){var result=new LinkedHashMap<String,String>();for(String table:List.of("tms_task_dispatch","tms_assignment","tms_resource_reservation","tms_dispatch_event","tms_idempotency","order_core","tom_order","cargo_item","transport_task","task_dependency","order_draft_revision","order_acceptance_event","api_idempotency","crm_customer","crm_contact","crm_address")){String order=table.equals("tms_task_dispatch")?"task_id":table.equals("tom_order")?"order_id":table.equals("task_dependency")?"task_id,predecessor_task_id":"id";result.put(table,jdbc.queryForObject("SELECT md5(coalesce(json_agg(row_to_json(t) ORDER BY "+order+")::text,'[]')) FROM logistics."+table+" t",String.class));}return result;}
    private Account account(String role)throws Exception{String username="exec-default-"+key().substring(0,12),password=UpmsTestData.password();try(var connection=dataSource.getConnection()){return new Account(UpmsTestData.insert(connection,encoder,username,role,password,false,Instant.now()),username,password);}}
    private void login(Browser browser,Account account)throws Exception{company(browser.post("/auth/login",fields("username",account.username(),"password",account.password()),null),200,"0000");}
    private JsonNode company(HttpResponse<String> reply,int status,String code){JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).as("Default execution policy HTTP code %s",body.get("code").asString()).isEqualTo(status);assertThat(body.propertyNames()).containsExactlyInAnyOrder("code","message","data","timestamp");assertThat(body.get("code").asString()).isEqualTo(code);assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));return body.get("data");}
    private JsonNode tom(HttpResponse<String> reply,int status){JsonNode body=json.readTree(reply.body());assertThat(reply.statusCode()).isEqualTo(status);TomContract.assertSchema("OrderEnvelope",body);return body.get("data");}
    private static Map<String,Object> fields(Object...values){var result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private String id(JsonNode node){return node.get("id").asString();}private UUID uuid(JsonNode node){return UUID.fromString(id(node));}private String key(){return UUID.randomUUID().toString();}
    private record Account(UUID id,String username,String password){}
    private final class Browser implements AutoCloseable{
        private final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).sslContext(TlsTestSupport.trustedContext()).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path)throws Exception{return send(path,null,null,null);}
        HttpResponse<String> post(String path,Object payload,String key)throws Exception{return send(path,json.writeValueAsString(payload),key,company(get("/auth/csrf"),200,"0000").get("token").asString());}
        private HttpResponse<String> send(String path,String payload,String key,String token)throws Exception{var request=HttpRequest.newBuilder(URI.create("https://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30)).header("Accept","application/json");if(payload!=null)request.header("Content-Type","application/json");if(key!=null)request.header("Idempotency-Key",key);if(token!=null)request.header("X-CSRF-TOKEN",token);return client.send(payload==null?request.GET().build():request.POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());}
        @Override public void close()throws Exception{client.shutdownNow();assertThat(client.awaitTermination(Duration.ofSeconds(5))).isTrue();}
    }
}
