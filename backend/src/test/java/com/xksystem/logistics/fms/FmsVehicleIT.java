package com.xksystem.logistics.fms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.adm.AdmQueries;
import com.xksystem.logistics.support.MutableTestClock;
import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.UpmsTestData;
import com.xksystem.logistics.upms.UpmsAuthorization;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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

/** Real TLS, PostgreSQL, role/CSRF filters and source locks. No dispatch implementation or reservation is implied. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FmsVehicleIT.TimeConfiguration.class)
class FmsVehicleIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",postgres::getJdbcUrl);registry.add("spring.datasource.username",postgres::getUsername);registry.add("spring.datasource.password",postgres::getPassword);TlsTestSupport.register(registry);}
    @TestConfiguration static class TimeConfiguration {@Bean @Primary MutableTestClock fmsClock(){return new MutableTestClock();}}
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    @Autowired VehicleQueries queries;
    @Autowired AdmQueries dictionaries;
    @Autowired UpmsAuthorization authorization;
    @Autowired PlatformTransactionManager transactionManager;
    private final JsonMapper json=JsonMapper.builder().build();
    private Account actor;private Browser admin;private UUID category;private List<UUID> types;
    private static final List<String> VEHICLE_FIELDS=List.of("id","plateNo","vehicleType","vehicleTypeCode","vehicleTypeLabel","vehicleTypeEnabled","capacityTon","registrationNo","registrationDate","inspectionDueDate","administrativeStatus","version","createdAt","createdBy","updatedAt","updatedBy");
    private static final List<String> OPTION_FIELDS=List.of("id","plateNo","vehicleType","vehicleTypeCode","vehicleTypeLabel","vehicleTypeEnabled","capacityTon","version");

    @BeforeEach void isolateSyntheticVehiclesAccountsAndTypeCatalog()throws Exception{
        jdbc.execute("TRUNCATE TABLE logistics.tms_exception_event_evidence,logistics.tms_exception_supplement_evidence,logistics.tms_exception_command,logistics.tms_exception_event,logistics.tms_exception_supplement_request,logistics.tms_exception,logistics.tms_execution_evidence_command,logistics.tms_execution_event_evidence,logistics.tms_execution_evidence,logistics.tms_execution_evidence_object,logistics.tms_execution_command,logistics.tms_execution_event,logistics.tms_execution,logistics.tms_resource_reservation,logistics.tms_dispatch_event,logistics.tms_idempotency,logistics.tms_assignment,logistics.tms_task_dispatch,logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle,logistics.order_core,logistics.tom_order,logistics.cargo_item,logistics.transport_task,logistics.task_dependency,logistics.order_draft_revision,logistics.api_idempotency,logistics.crm_contact,logistics.crm_address,logistics.crm_customer,logistics.adm_change_log,logistics.upms_audit,logistics.upms_account");
        jdbc.update("DELETE FROM logistics.adm_dict_item WHERE NOT read_only");
        jdbc.update("UPDATE logistics.adm_dict_category SET enabled=true,version=0 WHERE code='VEHICLE_TYPE'");
        category=jdbc.queryForObject("SELECT id FROM logistics.adm_dict_category WHERE code='VEHICLE_TYPE'",UUID.class);
        types=new ArrayList<>();int index=0;
        for(String label:List.of("合成車頭","合成拖板","合成貨車"))types.add(insertType(category,"SYNTHETIC_FMS_"+(++index),label,index));
        clock.reset();actor=account("ADMIN",false);admin=new Browser();login(admin,actor);
    }

    @Test void sixRoutesRequireNormalManagersWhileDriverAnonymousAndForcedPasswordHaveNoVehicleGrant()throws Exception{
        JsonNode initial=create(admin,fields("plateNo",plate(),"vehicleType",types.get(0).toString()));
        var paths=List.of("/fms/vehicles","/fms/vehicles/"+id(initial),"/fms/vehicle-options","/fms/vehicle-types");
        Browser anonymous=new Browser();for(String path:paths)response(anonymous.get(path),401);response(anonymous.write("POST","/fms/vehicles",minimal(),true),401);
        for(String role:List.of("ADMIN","DISPATCHER","DRIVER")){
            Browser member=new Browser();login(member,account(role,false));int status=role.equals("DRIVER")?403:200;
            for(String path:paths)response(member.get(path),status);
            response(member.write("POST","/fms/vehicles",minimal(),true),role.equals("DRIVER")?403:201);
            response(member.write("PATCH","/fms/vehicles/"+id(initial),fields("version",data(admin.get("/fms/vehicles/"+id(initial))).get("version").asLong(),"registrationNo","合成角色管理"),true),status);
            if(role.equals("DISPATCHER"))response(member.get("/adm/categories"),403);
            Browser temporary=new Browser();login(temporary,account(role,true));for(String path:paths)response(temporary.get(path),403);response(temporary.write("POST","/fms/vehicles",minimal(),true),403);
        }
        response(admin.write("POST","/fms/vehicles",minimal(),false),403);
        response(admin.send("PATCH","/fms/vehicles/"+id(initial),json.writeValueAsString(fields("version",1,"plateNo",plate())),"synthetic-invalid-csrf"),403);
    }

    @Test void minimalDefaultsAndTruePartialPatchesKeepIdentityAuditAndNullableUnknownValues()throws Exception{
        JsonNode vehicle=create(admin,minimal());vehicleShape(vehicle);assertThat(vehicle.get("version").asLong()).isZero();assertThat(vehicle.get("administrativeStatus").asString()).isEqualTo("AVAILABLE");
        for(String field:List.of("capacityTon","registrationNo","registrationDate","inspectionDueDate"))assertThat(vehicle.get(field).isNull()).isTrue();
        JsonNode changed=patch(admin,vehicle,fields("capacityTon","12.3400","registrationNo"," 非制式\u00a0車籍 ","registrationDate","2026-12-31","inspectionDueDate","2020-01-01"));
        assertThat(changed.get("registrationNo").asString()).isEqualTo("非制式\u00a0車籍");assertThat(changed.get("capacityTon").asString()).isEqualTo("12.3400");
        JsonNode cleared=patch(admin,changed,fields("capacityTon",null,"registrationNo","\ufeff\u00a0 ","inspectionDueDate",null));
        assertThat(cleared.get("registrationDate").asString()).isEqualTo("2026-12-31");assertThat(cleared.get("capacityTon").isNull()).isTrue();assertThat(cleared.get("registrationNo").isNull()).isTrue();assertThat(cleared.get("inspectionDueDate").isNull()).isTrue();
        assertThat(cleared.get("id")).isEqualTo(vehicle.get("id"));assertThat(cleared.get("createdBy").asString()).isEqualTo(actor.id().toString());assertThat(cleared.get("updatedBy").asString()).isEqualTo(actor.id().toString());
        response(admin.write("PATCH","/fms/vehicles/"+id(vehicle),fields("version",2),true),400);
        assertThat(admin.write("DELETE","/fms/vehicles/"+id(vehicle),null,true).statusCode()).isEqualTo(405);assertThat(count()).isEqualTo(1);
    }

    @Test void canonicalCasefoldPlateIsUniqueEvenWhenInactiveAndVersionedCorrectionsKeepUuid()throws Exception{
        JsonNode vehicle=create(admin,fields("plateNo","\u001c\ufeff e\u0301-ab  c \u202f\ufeff","vehicleType",types.get(0).toString()));assertThat(vehicle.get("plateNo").asString()).isEqualTo("É-AB  C");
        HttpResponse<String> duplicate=admin.write("POST","/fms/vehicles",fields("plateNo","é-ab  c","vehicleType",types.get(1).toString()),true);response(duplicate,409);assertThat(body(duplicate).get("data").has("plateNo")).isTrue();
        JsonNode inactive=patch(admin,vehicle,fields("administrativeStatus","INACTIVE"));response(admin.write("POST","/fms/vehicles",fields("plateNo","É-AB  C","vehicleType",types.get(2).toString()),true),409);
        JsonNode correction=patch(admin,inactive,fields("plateNo","  合成é新車牌  "));assertThat(id(correction)).isEqualTo(id(vehicle));assertThat(correction.get("plateNo").asString()).isEqualTo("合成É新車牌");
        JsonNode expanded=create(admin,fields("plateNo","ß".repeat(20),"vehicleType",types.get(0).toString()));assertThat(expanded.get("plateNo").asString()).isEqualTo("SS".repeat(20));
        response(admin.write("POST","/fms/vehicles",fields("plateNo","ß".repeat(20)+"a","vehicleType",types.get(0).toString()),true),400);
    }

    @Test void exactDecimalPrecisionScaleAndGregorianDatesAreVerifiedAgainstIndependentPostgresText()throws Exception{
        String tiny="0."+"0".repeat(29)+"1",large="12345678901234567890123456789.12";
        JsonNode vehicle=create(admin,fields("plateNo",plate(),"vehicleType",types.get(0).toString(),"capacityTon",tiny,"registrationDate","1500-01-02","inspectionDueDate","1582-10-10"));
        assertThat(vehicle.get("capacityTon").asString()).isEqualTo(tiny);assertThat(jdbc.queryForObject("SELECT capacity_ton::text FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(vehicle))).isEqualTo(tiny);
        assertThat(jdbc.queryForObject("SELECT registration_date::text FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(vehicle))).isEqualTo("1500-01-02");
        assertThat(jdbc.queryForObject("SELECT inspection_due_date::text FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(vehicle))).isEqualTo("1582-10-10");
        JsonNode bounds=patch(admin,vehicle,fields("capacityTon",large,"registrationDate","0001-01-01","inspectionDueDate","9999-12-31"));assertThat(bounds.get("capacityTon").asString()).isEqualTo(large);
        assertThat(jdbc.queryForObject("SELECT capacity_ton::text FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(vehicle))).isEqualTo(large);
        assertThat(jdbc.queryForObject("SELECT registration_date::text FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(vehicle))).isEqualTo("0001-01-01");assertThat(jdbc.queryForObject("SELECT inspection_due_date::text FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(vehicle))).isEqualTo("9999-12-31");
        JsonNode noLegalOrderPolicy=patch(admin,bounds,fields("registrationDate","9999-12-31","inspectionDueDate","0001-01-01","capacityTon","0"));
        assertThat(noLegalOrderPolicy.get("capacityTon").asString()).isEqualTo("0");assertThat(page(admin.get("/fms/vehicle-options")).size()).isEqualTo(1);
    }

    @Test void fixedClockSameValueAndScaleOnlyPatchesEachPersistExactlyOneNewVersion()throws Exception{
        JsonNode v0=create(admin,fields("plateNo",plate(),"vehicleType",types.get(0).toString(),"capacityTon","1.00"));
        JsonNode v1=patch(admin,v0,fields("plateNo",v0.get("plateNo").asString()));assertThat(v1.get("version").asLong()).isEqualTo(1);
        JsonNode v2=patch(admin,v1,fields("capacityTon","1.000"));assertThat(v2.get("version").asLong()).isEqualTo(2);assertThat(v2.get("capacityTon").asString()).isEqualTo("1.000");
        assertThat(jdbc.queryForObject("SELECT capacity_ton::text FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(v0))).isEqualTo("1.000");
        assertThat(jdbc.queryForObject("SELECT version FROM logistics.fms_vehicle WHERE id=?",Long.class,uuid(v0))).isEqualTo(2);
        assertThat(java.time.Instant.parse(v2.get("updatedAt").asString())).isAfter(java.time.Instant.parse(v1.get("updatedAt").asString()));
        JsonNode v3=patch(admin,v2,fields("capacityTon","0.0"));JsonNode v4=patch(admin,v3,fields("capacityTon","0.000"));assertThat(v4.get("version").asLong()).isEqualTo(4);assertThat(jdbc.queryForObject("SELECT capacity_ton::text FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(v0))).isEqualTo("0.000");
    }

    @Test void invalidBodiesAndSystemDerivedOccupancyFieldsNeverWritePartialVehicles()throws Exception{
        for(String field:List.of("id","version","createdAt","createdBy","updatedAt","updatedBy","vehicleTypeCode","vehicleTypeLabel","vehicleTypeEnabled","vehicleTypeId","driverId","assignmentId","reservationId","inUse")){
            Map<String,Object> request=minimal();request.put(field,"not-client-controlled");response(admin.write("POST","/fms/vehicles",request,true),400);
        }
        for(Map<String,Object> invalid:List.of(fields("plateNo",""),fields("plateNo","\ufeff "),fields("plateNo","x".repeat(41)),fields("plateNo","AB\u0000C"),fields("plateNo",null),fields("vehicleType",null),fields("vehicleType","1-1-1-1-1"),fields("vehicleType",UUID.randomUUID().toString()),fields("capacityTon",0),fields("capacityTon","-1"),fields("capacityTon","01"),fields("capacityTon","1e3"),fields("capacityTon","1\n"),fields("capacityTon","1".repeat(33)),fields("registrationNo","x".repeat(101)),fields("registrationNo","x\u0000y"),fields("registrationDate","0000-01-01"),fields("registrationDate","2026-02-30"),fields("inspectionDueDate","2026-1-01"),fields("administrativeStatus","IN_USE"),fields("administrativeStatus",null))){Map<String,Object> request=minimal();request.putAll(invalid);response(admin.write("POST","/fms/vehicles",request,true),400);}
        response(admin.raw("POST","/fms/vehicles","{\"plateNo\":\"A\",\"plateNo\":\"B\"}",true),400);response(admin.raw("POST","/fms/vehicles","{",true),400);
        response(admin.raw("POST","/fms/vehicles","{\"plateNo\":\"\\ud800\",\"vehicleType\":\""+types.get(0)+"\"}",true),400);
        assertThat(count()).isZero();
    }

    @Test void onlyActiveVehicleTypeCategoryItemsAreNewReferencesButArchivedLabelsRemainReadableAndMaintainable()throws Exception{
        JsonNode vehicle=create(admin,minimal());assertThat(data(admin.get("/fms/vehicle-types")).size()).isEqualTo(3);
        UUID wrongCategory=jdbc.queryForObject("SELECT id FROM logistics.adm_dict_item WHERE code='IMPORT'",UUID.class);
        response(admin.write("POST","/fms/vehicles",fields("plateNo",plate(),"vehicleType",wrongCategory.toString()),true),400);
        jdbc.update("UPDATE logistics.adm_dict_item SET label='合成車頭改名',enabled=false,version=version+1 WHERE id=?",types.get(0));
        JsonNode historical=data(admin.get("/fms/vehicles/"+id(vehicle)));assertThat(historical.get("vehicleTypeLabel").asString()).isEqualTo("合成車頭改名");assertThat(historical.get("vehicleTypeEnabled").asBoolean()).isFalse();
        assertThat(data(admin.get("/fms/vehicle-types")).size()).isEqualTo(2);
        JsonNode maintained=patch(admin,historical,fields("vehicleType",types.get(0).toString(),"registrationNo","封存分類仍可維護"));
        response(admin.write("POST","/fms/vehicles",fields("plateNo",plate(),"vehicleType",types.get(0).toString()),true),400);
        JsonNode other=create(admin,fields("plateNo",plate(),"vehicleType",types.get(1).toString()));response(admin.write("PATCH","/fms/vehicles/"+id(other),fields("version",0,"vehicleType",types.get(0).toString()),true),400);
        jdbc.update("UPDATE logistics.adm_dict_category SET enabled=false,version=version+1 WHERE id=?",category);
        assertThat(data(admin.get("/fms/vehicle-types")).size()).isZero();JsonNode parentArchived=patch(admin,maintained,fields("plateNo",plate()));assertThat(parentArchived.get("vehicleTypeEnabled").asBoolean()).isFalse();
        assertThat(page(admin.get("/fms/vehicle-options")).size()).isEqualTo(2);
        response(admin.write("PATCH","/fms/vehicles/"+id(parentArchived),fields("version",parentArchived.get("version").asLong(),"vehicleType",types.get(2).toString()),true),400);
    }

    @Test void optionsMeanOnlyAdministrativeAvailableEvenWithPastInspectionOrArchivedType()throws Exception{
        JsonNode available=create(admin,fields("plateNo","SYN-AVAILABLE","vehicleType",types.get(0).toString(),"inspectionDueDate","0001-01-01"));
        create(admin,fields("plateNo","SYN-MAINTENANCE","vehicleType",types.get(1).toString(),"administrativeStatus","MAINTENANCE"));create(admin,fields("plateNo","SYN-INACTIVE","vehicleType",types.get(2).toString(),"administrativeStatus","INACTIVE"));
        jdbc.update("UPDATE logistics.adm_dict_item SET enabled=false WHERE id=?",types.get(0));
        JsonNode choices=page(admin.get("/fms/vehicle-options"));assertThat(choices.size()).isEqualTo(1);assertThat(choices.get(0).get("id").asString()).isEqualTo(id(available));assertThat(choices.get(0).get("vehicleTypeEnabled").asBoolean()).isFalse();assertThat(choices.get(0).propertyNames()).containsExactlyInAnyOrderElementsOf(OPTION_FIELDS);
        assertThat(page(admin.get("/fms/vehicle-options?q=SYN-MAINTENANCE")).size()).isZero();assertThat(page(admin.get("/fms/vehicle-options?vehicleType="+types.get(2))).size()).isZero();
        for(String query:List.of("administrativeStatus=MAINTENANCE","administrativeStatus=AVAILABLE","enabled=false","sort=plateNo,asc"))response(admin.get("/fms/vehicle-options?"+query),400);
        assertThat(page(admin.get("/fms/vehicles?administrativeStatus=MAINTENANCE")).size()).isEqualTo(1);
    }

    @Test void literalUnicodeSearchFixedStablePagingAndFiltersCannotBecomeSqlWildcards()throws Exception{
        for(String plate:List.of("SYN-C","SYN-A","SYN-B","SYN-%_\\","straße"))create(admin,fields("plateNo",plate,"vehicleType",types.get(0).toString(),"registrationNo",plate.contains("%")?" e\u0301Reg%_\\ ":" e\u0301RegPlain "));
        List<String> expected=jdbc.queryForList("SELECT id::text FROM logistics.fms_vehicle ORDER BY plate_no,id",String.class);List<String> actual=new ArrayList<>();
        for(int page=0;page<3;page++){JsonNode response=data(admin.get("/fms/vehicles?page="+page+"&size=2"));assertThat(response.get("totalElements").asLong()).isEqualTo(5);for(JsonNode vehicle:response.get("content"))actual.add(id(vehicle));}
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(page(admin.get("/fms/vehicles?q="+encode("%_\\"))).size()).isEqualTo(1);
        assertThat(page(admin.get("/fms/vehicles?q="+encode("straße"))).get(0).get("plateNo").asString()).isEqualTo("STRASSE");
        assertThat(page(admin.get("/fms/vehicles?q="+encode(" e\u0301Reg%_\\ "))).size()).isEqualTo(1);
        assertThat(page(admin.get("/fms/vehicles?vehicleType="+types.get(1))).size()).isZero();assertThat(page(admin.get("/fms/vehicles?page=100000&size=100")).size()).isZero();
        // A percent sign does not expand an otherwise nonexistent literal prefix.
        create(admin,fields("plateNo","NO-MATCHED","vehicleType",types.get(1).toString(),"registrationNo","NO_MATCHED"));
        assertThat(page(admin.get("/fms/vehicles?q="+encode("NO_MATCH%"))).size()).isZero();
    }

    @Test void everyQueryProtocolAndStrictPatchBoundaryIsRejectedWithoutMutation()throws Exception{
        JsonNode vehicle=create(admin,minimal());String path="/fms/vehicles/"+id(vehicle);
        for(String query:List.of("page=-1","page=100001","page=0.5","size=0","size=101","page=0&page=0","sort=plateNo,asc","q="+"x".repeat(101),"vehicleType=1-1-1-1-1","administrativeStatus=IN_USE","unknown=1"))response(admin.get("/fms/vehicles?"+query),400);
        for(String exact:List.of(path,"/fms/vehicle-types"))response(admin.get(exact+"?unknown=1"),400);
        response(admin.write("POST","/fms/vehicles?unknown=1",minimal(),true),400);response(admin.write("PATCH",path+"?unknown=1",fields("version",0,"plateNo",plate()),true),400);
        response(admin.get("/fms/vehicles/1-1-1-1-1"),400);response(admin.get("/fms/vehicles/"+UUID.randomUUID()),404);
        for(Object value:List.of(-1,"0",0.1,9007199254740992L))response(admin.write("PATCH",path,fields("version",value,"plateNo",plate()),true),400);
        for(String field:List.of("id","createdBy","vehicleTypeCode","vehicleTypeLabel","vehicleTypeEnabled","assignmentId"))response(admin.write("PATCH",path,fields("version",0,field,"not-controlled"),true),400);
        for(String field:List.of("plateNo","vehicleType","administrativeStatus"))response(admin.write("PATCH",path,fields("version",0,field,null),true),400);
        assertThat(data(admin.get(path))).isEqualTo(vehicle);
    }

    @Test void concurrentCasefoldDuplicatesHaveOneWinnerAndNoInactiveUniquenessException()throws Exception{
        String token=admin.csrf();var first=fields("plateNo","ß-race","vehicleType",types.get(0).toString());var second=fields("plateNo","SS-RACE","vehicleType",types.get(1).toString());
        try(var pool=Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(List.<Callable<HttpResponse<String>>>of(()->admin.send("POST","/fms/vehicles",json.writeValueAsString(first),token),()->admin.send("POST","/fms/vehicles",json.writeValueAsString(second),token)));
            assertThat(List.of(results.get(0).get().statusCode(),results.get(1).get().statusCode())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(count()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT plate_no FROM logistics.fms_vehicle",String.class)).isEqualTo("SS-RACE");
    }

    @Test void concurrentVersionsAllowOneUpdateAndFailedOrMaximumWritesKeepTheWholeRecord()throws Exception{
        JsonNode original=create(admin,minimal());String token=admin.csrf();String path="/fms/vehicles/"+id(original);
        try(var pool=Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(List.<Callable<HttpResponse<String>>>of(()->admin.send("PATCH",path,json.writeValueAsString(fields("version",0,"registrationNo","合成甲")),token),()->admin.send("PATCH",path,json.writeValueAsString(fields("version",0,"registrationNo","合成乙")),token)));
            assertThat(List.of(results.get(0).get().statusCode(),results.get(1).get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        JsonNode latest=data(admin.get(path));assertThat(latest.get("version").asLong()).isEqualTo(1);
        HttpResponse<String> conflict=admin.write("PATCH",path,fields("version",0,"plateNo",plate()),true);response(conflict,409);assertThat(body(conflict).get("data").has("version")).isTrue();assertThat(data(admin.get(path))).isEqualTo(latest);
        jdbc.update("UPDATE logistics.fms_vehicle SET version=? WHERE id=?",VehicleValidation.MAX_VERSION,uuid(original));JsonNode exhausted=data(admin.get(path));response(admin.write("PATCH",path,fields("version",VehicleValidation.MAX_VERSION,"plateNo",plate()),true),409);assertThat(data(admin.get(path))).isEqualTo(exhausted);
    }

    @Test void disabledNewTypeRollsBackEarlierPlateCapacityAndAuditChangesAsOneTransaction()throws Exception{
        JsonNode vehicle=create(admin,minimal());jdbc.update("UPDATE logistics.adm_dict_item SET enabled=false WHERE id=?",types.get(1));
        response(admin.write("PATCH","/fms/vehicles/"+id(vehicle),fields("version",0,"plateNo","REJECTED-PLATE","capacityTon","2.500","vehicleType",types.get(1).toString()),true),400);
        assertThat(data(admin.get("/fms/vehicles/"+id(vehicle)))).isEqualTo(vehicle);assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.fms_vehicle WHERE plate_no='REJECTED-PLATE'",Long.class)).isZero();
    }

    @Test void admCategoryAndItemDisableWinningTheirRowLockRejectTheNewVehicleReference()throws Exception{
        String token=admin.csrf();
        for(String table:List.of("adm_dict_category","adm_dict_item")){
            UUID target=table.equals("adm_dict_category")?category:types.get(0);
            try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
                held.setAutoCommit(false);
                try(var statement=held.prepareStatement("UPDATE logistics."+table+" SET enabled=false,version=version+1 WHERE id=?")){statement.setObject(1,target);statement.executeUpdate();}
                var pending=pool.submit(()->admin.send("POST","/fms/vehicles",json.writeValueAsString(minimal()),token));awaitDatabaseLock(table);held.commit();response(pending.get(15,TimeUnit.SECONDS),400);
            }
            assertThat(count()).isZero();jdbc.update("UPDATE logistics."+table+" SET enabled=true,version=version+1 WHERE id=?",target);
        }
    }

    @Test void successfulCreationKeepsBothAdmReferenceLocksUntilItsRealTransactionCommits()throws Exception{
        String token=admin.csrf();createPauseTrigger();var pool=Executors.newFixedThreadPool(3);
        try(var gate=dataSource.getConnection()){
            advisory(gate,true);
            try{
                var created=pool.submit(()->admin.send("POST","/fms/vehicles",json.writeValueAsString(minimal()),token));awaitDatabaseLock("fms_vehicle");
                var disableItem=pool.submit(()->jdbc.update("UPDATE logistics.adm_dict_item SET enabled=false,version=version+1 WHERE id=?",types.get(0)));
                var disableCategory=pool.submit(()->jdbc.update("UPDATE logistics.adm_dict_category SET enabled=false,version=version+1 WHERE id=?",category));
                awaitDatabaseLock("adm_dict_item");awaitDatabaseLock("adm_dict_category");assertThat(disableItem.isDone()).isFalse();assertThat(disableCategory.isDone()).isFalse();advisory(gate,false);
                HttpResponse<String> response=created.get(15,TimeUnit.SECONDS);response(response,201);assertThat(disableItem.get(15,TimeUnit.SECONDS)).isEqualTo(1);assertThat(disableCategory.get(15,TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(data(admin.get("/fms/vehicles/"+id(data(response)))).get("vehicleTypeEnabled").asBoolean()).isFalse();assertThat(page(admin.get("/fms/vehicle-options")).size()).isEqualTo(1);
            }finally{advisory(gate,false);}
        }finally{dropPauseTrigger();pool.shutdownNow();}
    }

    @Test void publicVehicleSourceRequiresWritableCallerTransactionAndOnlyAdministrativeAvailability()throws Exception{
        JsonNode vehicle=create(admin,fields("plateNo",plate(),"vehicleType",types.get(0).toString(),"inspectionDueDate","0001-01-01"));UUID id=uuid(vehicle);
        assertThat(queries.findCurrent(UUID.randomUUID())).isEmpty();assertThat(queries.findCurrent(null)).isEmpty();
        assertThatThrownBy(()->queries.requireAdministrativelyAvailable(id)).isInstanceOf(IllegalTransactionStateException.class);
        var readonly=new TransactionTemplate(transactionManager);readonly.setReadOnly(true);assertThatThrownBy(()->readonly.execute(status->queries.requireAdministrativelyAvailable(id))).isInstanceOf(IllegalTransactionStateException.class);
        var transaction=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->transaction.execute(status->queries.requireAdministrativelyAvailable(UUID.randomUUID()))).isInstanceOfSatisfying(UpmsException.class,error->assertThat(error.status()).isEqualTo(404));
        jdbc.update("UPDATE logistics.adm_dict_item SET enabled=false WHERE id=?",types.get(0));
        var selected=transaction.execute(status->{authorization.requireVehicleManagerForWrite(principal(actor));return queries.requireAdministrativelyAvailable(id);});
        assertThat(selected.administrativeStatus()).isEqualTo(AdministrativeStatus.AVAILABLE);assertThat(selected.vehicleTypeEnabled()).isFalse();
        for(String state:List.of("MAINTENANCE","INACTIVE")){
            jdbc.update("UPDATE logistics.fms_vehicle SET administrative_status=?,version=version+1 WHERE id=?",state,id);
            assertThat(queries.findCurrent(id).orElseThrow().administrativeStatus().name()).isEqualTo(state);
            assertThatThrownBy(()->transaction.execute(status->{authorization.requireVehicleManagerForWrite(principal(actor));return queries.requireAdministrativelyAvailable(id);})).isInstanceOfSatisfying(UpmsException.class,error->{assertThat(error.status()).isEqualTo(400);assertThat(((Map<?,?>)error.data()).containsKey("vehicleId")).isTrue();});
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics' AND tablename='vehicle_pair'",Long.class)).isZero();
        // The source facade remains administrative only; M3-04 owns its separate, initially empty TMS tables.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics' AND tablename IN ('tms_task_dispatch','tms_assignment','tms_resource_reservation','tms_dispatch_event','tms_idempotency')",Long.class)).isEqualTo(5);
        for(String table:List.of("tms_task_dispatch","tms_assignment","tms_resource_reservation","tms_dispatch_event","tms_idempotency"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics."+table,Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics' AND tablename='fms_driver'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.fms_driver",Long.class)).isZero();
    }

    @Test void preloadedVehicleAndDictionaryStateAreRefreshedBeforeReturningALockedReference()throws Exception{
        JsonNode vehicle=create(admin,minimal());UUID id=uuid(vehicle);var transaction=new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status->{
            authorization.requireVehicleManagerForWrite(principal(actor));var old=queries.findCurrent(id).orElseThrow();assertThat(old.vehicleTypeEnabled()).isTrue();
            external("UPDATE logistics.adm_dict_item SET enabled=false,label='合成新歷史標籤',version=version+1 WHERE id=?",types.get(0));
            external("UPDATE logistics.fms_vehicle SET plate_no='SYN-FRESH-REFERENCE',version=version+1 WHERE id=?",id);
            var fresh=queries.requireAdministrativelyAvailable(id);assertThat(fresh.plateNo()).isEqualTo("SYN-FRESH-REFERENCE");assertThat(fresh.version()).isEqualTo(1);assertThat(fresh.vehicleTypeLabel()).isEqualTo("合成新歷史標籤");assertThat(fresh.vehicleTypeEnabled()).isFalse();assertThat(old.plateNo()).isEqualTo(vehicle.get("plateNo").asString());
        });
        assertThatThrownBy(()->transaction.execute(status->{
            authorization.requireVehicleManagerForWrite(principal(actor));queries.findCurrent(id).orElseThrow();external("UPDATE logistics.fms_vehicle SET administrative_status='MAINTENANCE',version=version+1 WHERE id=?",id);return queries.requireAdministrativelyAvailable(id);
        })).isInstanceOfSatisfying(UpmsException.class,error->assertThat(error.status()).isEqualTo(400));
    }

    @Test void referenceLockMakesAConcurrentStatusChangeWaitAndReturnedSnapshotDoesNotMutate()throws Exception{
        JsonNode vehicle=create(admin,minimal());UUID id=uuid(vehicle);var transaction=new TransactionTemplate(transactionManager);var pool=Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>> update=new java.util.concurrent.atomic.AtomicReference<>();
        try{
            VehicleQueries.Snapshot before=transaction.execute(status->{
                authorization.requireVehicleManagerForWrite(principal(actor));var snapshot=queries.requireAdministrativelyAvailable(id);
                update.set(pool.submit(()->jdbc.update("UPDATE logistics.fms_vehicle SET administrative_status='INACTIVE',plate_no='SYN-AFTER-COMMIT',version=version+1 WHERE id=?",id)));
                awaitDatabaseLock("fms_vehicle");assertThat(update.get().isDone()).isFalse();return snapshot;
            });
            assertThat(update.get().get(15,TimeUnit.SECONDS)).isEqualTo(1);assertThat(before.administrativeStatus()).isEqualTo(AdministrativeStatus.AVAILABLE);assertThat(before.plateNo()).isEqualTo(vehicle.get("plateNo").asString());
            var current=queries.findCurrent(id).orElseThrow();assertThat(current.administrativeStatus()).isEqualTo(AdministrativeStatus.INACTIVE);assertThat(current.plateNo()).isEqualTo("SYN-AFTER-COMMIT");assertThat(before.version()).isZero();assertThat(current.version()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    @Test void publicAdmHistoricalLookupIsCategoryBoundAndItsReferenceGuardRefreshesCachedParents()throws Exception{
        assertThat(dictionaries.findItem("VEHICLE_TYPE",types.get(0)).orElseThrow().enabled()).isTrue();assertThat(dictionaries.findItem("ORDER_TYPE",types.get(0))).isEmpty();assertThat(dictionaries.findItem("VEHICLE_TYPE",UUID.randomUUID())).isEmpty();
        assertThatThrownBy(()->dictionaries.requireEnabledItemForNewReference("VEHICLE_TYPE",types.get(0))).isInstanceOf(IllegalTransactionStateException.class);
        var readonly=new TransactionTemplate(transactionManager);readonly.setReadOnly(true);assertThatThrownBy(()->readonly.execute(status->dictionaries.requireEnabledItemForNewReference("VEHICLE_TYPE",types.get(0)))).isInstanceOf(IllegalTransactionStateException.class);
        var transaction=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->transaction.execute(status->{
            authorization.requireVehicleManagerForWrite(principal(actor));dictionaries.findItem("VEHICLE_TYPE",types.get(0)).orElseThrow();external("UPDATE logistics.adm_dict_category SET enabled=false,version=version+1 WHERE id=?",category);
            assertThat(dictionaries.findItem("VEHICLE_TYPE",types.get(0)).orElseThrow().enabled()).isFalse();return dictionaries.requireEnabledItemForNewReference("VEHICLE_TYPE",types.get(0));
        })).isInstanceOfSatisfying(UpmsException.class,error->assertThat(error.status()).isEqualTo(400));
        assertThat(dictionaries.findItem("VEHICLE_TYPE",types.get(0)).orElseThrow().label()).isEqualTo("合成車頭");
    }

    @Test void revokedActorWaitingForCanonicalAuthorizationCannotCreateOrPartiallyPatchAVehicle()throws Exception{
        JsonNode vehicle=create(admin,minimal());String token=admin.csrf();
        try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
            held.setAutoCommit(false);try(var statement=held.createStatement()){statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();}
            var pending=pool.submit(()->admin.send("PATCH","/fms/vehicles/"+id(vehicle),json.writeValueAsString(fields("version",0,"plateNo","SYN-REVOKED")),token));awaitDatabaseLock("upms_lock");
            try(var statement=held.prepareStatement("UPDATE logistics.upms_account SET auth_version=auth_version+1,role='DRIVER' WHERE id=?")){statement.setObject(1,actor.id());statement.executeUpdate();}held.commit();response(pending.get(15,TimeUnit.SECONDS),401);
        }
        assertThat(jdbc.queryForObject("SELECT plate_no FROM logistics.fms_vehicle WHERE id=?",String.class,uuid(vehicle))).isEqualTo(vehicle.get("plateNo").asString());assertThat(jdbc.queryForObject("SELECT version FROM logistics.fms_vehicle WHERE id=?",Long.class,uuid(vehicle))).isZero();
        response(admin.get("/auth/csrf"),200);
        Account independentlyRevoked=account("ADMIN",false);Browser revoked=new Browser();login(revoked,independentlyRevoked);String cachedToken=revoked.csrf();
        jdbc.update("UPDATE logistics.upms_account SET auth_version=auth_version+1 WHERE id=?",independentlyRevoked.id());
        // UPMS rejects the old session's CSRF token first; TOM alone maps this case to its legacy 401.
        response(revoked.send("POST","/fms/vehicles",json.writeValueAsString(minimal()),cachedToken),403);response(revoked.get("/auth/csrf"),200);
        response(revoked.write("POST","/fms/vehicles",minimal(),true),401);assertThat(count()).isEqualTo(1);
    }

    @Test void databaseConstraintsKeepUniquePlateAndForeignKeysAndAdministrativeDomain()throws Exception{
        JsonNode vehicle=create(admin,minimal());UUID id=uuid(vehicle);
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.fms_vehicle(id,plate_no,vehicle_type_id,administrative_status,version,created_at,created_by,updated_at,updated_by) SELECT ?,plate_no,vehicle_type_id,administrative_status,0,created_at,created_by,updated_at,updated_by FROM logistics.fms_vehicle WHERE id=?",UUID.randomUUID(),id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.fms_vehicle SET vehicle_type_id=? WHERE id=?",UUID.randomUUID(),id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.fms_vehicle SET created_by=? WHERE id=?",UUID.randomUUID(),id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.fms_vehicle SET capacity_ton=-1 WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.fms_vehicle SET capacity_ton=?::numeric WHERE id=?","1".repeat(33),id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE logistics.fms_vehicle SET administrative_status='IN_USE' WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM logistics.adm_dict_item WHERE id=?",types.get(0))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(data(admin.get("/fms/vehicles/"+id))).isEqualTo(vehicle);
        String exact="1234567890123456789012345678.120";
        assertThat(exact.length()).isEqualTo(32);jdbc.update("UPDATE logistics.fms_vehicle SET capacity_ton=?::numeric WHERE id=?",exact,id);
        assertThat(jdbc.queryForObject("SELECT capacity_ton::text FROM logistics.fms_vehicle WHERE id=?",String.class,id)).isEqualTo(exact);
    }

    @Test void failedStorageRollsBackAllFieldsVersionAndAuditWithoutExposingSql()throws Exception{
        JsonNode vehicle=create(admin,minimal());String path="/fms/vehicles/"+id(vehicle);
        jdbc.execute("CREATE FUNCTION logistics.test_reject_fms_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic vehicle storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER test_reject_fms_change BEFORE UPDATE ON logistics.fms_vehicle FOR EACH ROW EXECUTE FUNCTION logistics.test_reject_fms_change()");
        try{
            var failed=admin.write("PATCH",path,fields("version",0,"plateNo","SYN-ROLLBACK","capacityTon","2.500","registrationNo","合成拒絕內容"),true);
            assertThat(failed.statusCode()).isBetween(500,599);assertThat(failed.body()).doesNotContain("synthetic vehicle storage failure","UPDATE logistics","Hibernate","SQLException");
        }finally{jdbc.execute("DROP TRIGGER test_reject_fms_change ON logistics.fms_vehicle");jdbc.execute("DROP FUNCTION logistics.test_reject_fms_change()");}
        assertThat(data(admin.get(path))).isEqualTo(vehicle);assertThat(count()).isEqualTo(1);
    }

    @Test void fmsUsesPublicModuleFacadesAndOwnsNoCrossModulePersistence()throws Exception{
        try(var classes=java.nio.file.Files.walk(java.nio.file.Path.of("target/classes/com/xksystem/logistics/fms"))){
            for(var file:classes.filter(value->value.toString().endsWith(".class")).toList()){
                String constants=new String(java.nio.file.Files.readAllBytes(file),StandardCharsets.ISO_8859_1);
                for(String forbidden:List.of("com/xksystem/logistics/adm/internal/","com/xksystem/logistics/upms/internal/","com/xksystem/logistics/crm/internal/","com/xksystem/logistics/tom/internal/","com/xksystem/logistics/tms/internal/"))assertThat(constants.contains(forbidden)).as("FMS owns its persistence: %s",file.getFileName()).isFalse();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='logistics'",Long.class)).isEqualTo(40);
    }

    private Map<String,Object> minimal(){return fields("plateNo",plate(),"vehicleType",types.get(0).toString());}
    private static Map<String,Object> fields(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private String plate(){return "SYN-"+UUID.randomUUID().toString().substring(0,12).toUpperCase(java.util.Locale.ROOT);}
    private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private String id(JsonNode value){return value.get("id").asString();}
    private UUID uuid(JsonNode value){return UUID.fromString(id(value));}
    private long count(){return jdbc.queryForObject("SELECT count(*) FROM logistics.fms_vehicle",Long.class);}
    private JsonNode body(HttpResponse<String> response){return json.readTree(response.body());}
    private JsonNode data(HttpResponse<String> response){return body(response).get("data");}
    private JsonNode page(HttpResponse<String> response){response(response,200);JsonNode value=data(response);assertThat(value.propertyNames()).containsExactlyInAnyOrder("content","page","size","totalElements","totalPages");return value.get("content");}
    private void response(HttpResponse<String> reply,int status){assertThat(reply.statusCode()).as("FMS HTTP status").isEqualTo(status);JsonNode value=body(reply);assertThat(value.propertyNames()).containsExactlyInAnyOrder("code","message","data","timestamp");assertThat(value.get("code").asString()).isEqualTo(status<400?"0000":"E0"+status);assertThat(java.time.OffsetDateTime.parse(value.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));}
    private void vehicleShape(JsonNode value){assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(VEHICLE_FIELDS);assertThat(UUID.fromString(value.get("id").asString())).isNotNull();}
    private JsonNode create(Browser browser,Map<String,Object> request)throws Exception{var response=browser.write("POST","/fms/vehicles",request,true);response(response,201);JsonNode vehicle=data(response);vehicleShape(vehicle);return vehicle;}
    private JsonNode patch(Browser browser,JsonNode previous,Map<String,Object> changes)throws Exception{var request=fields("version",previous.get("version").asLong());request.putAll(changes);var response=browser.write("PATCH","/fms/vehicles/"+id(previous),request,true);response(response,200);JsonNode vehicle=data(response);vehicleShape(vehicle);assertThat(vehicle.get("version").asLong()).isEqualTo(previous.get("version").asLong()+1);return vehicle;}
    private UUID insertType(UUID category,String code,String label,int order){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,?,?,?,true,?,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,category,code,label,order);return id;}
    private Account account(String role,boolean temporary)throws Exception{String username="fms-test-"+UUID.randomUUID().toString().substring(0,16),password=UpmsTestData.password();try(var connection=dataSource.getConnection()){return new Account(UpmsTestData.insert(connection,encoder,username,role,password,temporary,clock.instant()),username,password);}}
    private void login(Browser browser,Account account)throws Exception{response(browser.write("POST","/auth/login",fields("username",account.username(),"password",account.password()),true),200);}
    private SessionPrincipal principal(Account account){return new SessionPrincipal(account.id(),0,clock.instant(),clock.instant());}
    private record Account(UUID id,String username,String password){}
    private final class Browser{
        private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).sslContext(TlsTestSupport.trustedContext()).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,null);}
        String csrf()throws Exception{var reply=get("/auth/csrf");response(reply,200);return data(reply).get("token").asString();}
        HttpResponse<String> write(String method,String path,Map<String,Object> value,boolean csrf)throws Exception{return raw(method,path,value==null?null:json.writeValueAsString(value),csrf);}
        HttpResponse<String> raw(String method,String path,String value,boolean csrf)throws Exception{return send(method,path,value,csrf?csrf():null);}
        HttpResponse<String> send(String method,String path,String value,String token)throws Exception{
            var request=HttpRequest.newBuilder(URI.create("https://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30)).header("Accept","application/json");if(value!=null)request.header("Content-Type","application/json");if(token!=null)request.header("X-CSRF-TOKEN",token);
            return client.send(request.method(method,value==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(value)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    private void external(String sql,Object...args){try(var connection=dataSource.getConnection()){new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true)).update(sql,args);}catch(java.sql.SQLException failure){throw new IllegalStateException("Could not update isolated synthetic source",failure);}}
    private void awaitDatabaseLock(String table){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);try(var observer=dataSource.getConnection();var statement=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")){assertThat(observer.getAutoCommit()).isTrue();statement.setString(1,"%"+table+"%");while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getLong(1)>0)return;}Thread.sleep(20);}}catch(Exception failure){throw new IllegalStateException("Could not observe isolated FMS source lock",failure);}throw new AssertionError("Expected real database lock for "+table);}
    private void createPauseTrigger(){jdbc.execute("CREATE FUNCTION logistics.test_pause_fms_create() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202603,1); RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_pause_fms_create BEFORE INSERT ON logistics.fms_vehicle FOR EACH ROW EXECUTE FUNCTION logistics.test_pause_fms_create()");}
    private void dropPauseTrigger(){jdbc.execute("DROP TRIGGER test_pause_fms_create ON logistics.fms_vehicle");jdbc.execute("DROP FUNCTION logistics.test_pause_fms_create()");}
    private void advisory(java.sql.Connection connection,boolean acquire)throws java.sql.SQLException{try(var statement=connection.createStatement()){statement.execute("SELECT pg_advisory_"+(acquire?"lock":"unlock")+"(202603,1)");}}
}
