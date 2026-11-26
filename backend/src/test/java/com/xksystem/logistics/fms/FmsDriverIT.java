package com.xksystem.logistics.fms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.adm.AdmQueries;
import com.xksystem.logistics.support.MutableTestClock;
import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.UpmsTestData;
import com.xksystem.logistics.upms.UpmsAuthorization;
import com.xksystem.logistics.upms.DriverAccountQueries;
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
@Import(FmsDriverIT.TimeConfiguration.class)
class FmsDriverIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",postgres::getJdbcUrl);registry.add("spring.datasource.username",postgres::getUsername);registry.add("spring.datasource.password",postgres::getPassword);TlsTestSupport.register(registry);}
    @TestConfiguration static class TimeConfiguration {@Bean @Primary MutableTestClock driverClock(){return new MutableTestClock();}}
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableTestClock clock;
    @Autowired DriverQueries queries;
    @Autowired DriverAccountQueries accounts;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired AdmQueries dictionaries;
    @Autowired UpmsAuthorization authorization;
    @Autowired PlatformTransactionManager transactionManager;
    private final JsonMapper json=JsonMapper.builder().build();
    private Account actor;private Browser admin;private UUID category;private List<UUID> types;
    private static final List<String> FULL=List.of("id","driverCode","name","phone","email","accountId","accountUsername","accountDisplayName","accountBindingValid","employmentStatus","dutyStatus","licenseType","licenseTypeCode","licenseTypeLabel","licenseTypeEnabled","licenseReference","licenseDueDate","version","createdAt","createdBy","updatedAt","updatedBy");
    private static final List<String> SUMMARY=FULL.stream().filter(value->!List.of("licenseReference","createdAt","createdBy","updatedAt","updatedBy").contains(value)).toList();
    private static final List<String> SELF=List.of("id","driverCode","name","phone","email","employmentStatus","dutyStatus","licenseType","licenseTypeCode","licenseTypeLabel","licenseTypeEnabled","licenseDueDate");
    private static final List<String> OPTION=List.of("id","driverCode","name","licenseType","licenseTypeCode","licenseTypeLabel","licenseTypeEnabled","licenseDueDate","version");

    @BeforeEach void isolateSyntheticDriversAccountsAndLicenseCatalog()throws Exception{
        jdbc.execute("TRUNCATE TABLE logistics.tms_exception_event_evidence,logistics.tms_exception_supplement_evidence,logistics.tms_exception_command,logistics.tms_exception_event,logistics.tms_exception_supplement_request,logistics.tms_exception,logistics.tms_execution_evidence_command,logistics.tms_execution_event_evidence,logistics.tms_execution_evidence,logistics.tms_execution_evidence_object,logistics.tms_execution_command,logistics.tms_execution_event,logistics.tms_execution,logistics.tms_resource_reservation,logistics.tms_dispatch_event,logistics.tms_idempotency,logistics.tms_assignment,logistics.tms_task_dispatch,logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle,logistics.order_core,logistics.tom_order,logistics.cargo_item,logistics.transport_task,logistics.task_dependency,logistics.order_draft_revision,logistics.api_idempotency,logistics.crm_contact,logistics.crm_address,logistics.crm_customer,logistics.adm_change_log,logistics.upms_audit,logistics.upms_account");
        jdbc.update("DELETE FROM logistics.adm_dict_item WHERE NOT read_only");jdbc.update("UPDATE logistics.adm_dict_category SET enabled=true,version=0 WHERE NOT read_only");
        category=jdbc.queryForObject("SELECT id FROM logistics.adm_dict_category WHERE code='DRIVER_LICENSE_TYPE'",UUID.class);types=new ArrayList<>();
        types.add(insertType(category,"SYNTHETIC_LICENSE_A","合成駕照類別甲",1));types.add(insertType(category,"SYNTHETIC_LICENSE_B","合成駕照類別乙",2));
        clock.reset();actor=account("ADMIN",false);admin=new Browser();login(admin,actor);
    }

    @Test void managerOperationsAndActorDerivedSelfHaveDistinctNormalRolesAndKeepCsrfAndPasswordBarriers()throws Exception{
        JsonNode initial=create(admin,minimal());List<String> reads=List.of("/fms/drivers","/fms/drivers/"+id(initial),"/fms/driver-options","/fms/driver-license-types","/fms/driver-account-options");
        Browser anonymous=new Browser();for(String path:reads)response(anonymous.get(path),401);response(anonymous.get("/fms/drivers/me"),401);response(anonymous.write("POST","/fms/drivers",minimal(),true),401);
        for(String role:List.of("ADMIN","DISPATCHER","DRIVER")){
            Browser member=new Browser();login(member,account(role,false));boolean manager=!role.equals("DRIVER");
            for(String path:reads)response(member.get(path),manager?200:403);
            response(member.write("POST","/fms/drivers",minimal(),true),manager?201:403);
            response(member.write("PATCH","/fms/drivers/"+id(initial),fields("version",data(admin.get("/fms/drivers/"+id(initial))).get("version").asLong(),"name","合成管理修改"),true),manager?200:403);
            response(member.get("/fms/drivers/me"),manager?403:404);
            if(role.equals("DISPATCHER")){response(member.get("/upms/accounts"),403);response(member.get("/adm/categories"),403);}
            Browser temporary=new Browser();login(temporary,account(role,true));for(String path:reads)response(temporary.get(path),403);response(temporary.get("/fms/drivers/me"),403);response(temporary.write("POST","/fms/drivers",minimal(),true),403);
        }
        response(admin.write("POST","/fms/drivers",minimal(),false),403);
        response(admin.send("PATCH","/fms/drivers/"+id(initial),json.writeValueAsString(fields("version",0,"name","合成拒絕")),"synthetic-invalid-csrf"),403);
    }

    @Test void minimalUnboundMasterDefaultsAndExactSummaryOptionAndSelfPrivacyDoNotExposeLicenseReferenceOrAccounts()throws Exception{
        JsonNode driver=create(admin,minimal());assertThat(driver.get("employmentStatus").asString()).isEqualTo("ACTIVE");assertThat(driver.get("dutyStatus").asString()).isEqualTo("OFF_DUTY");
        for(String key:List.of("phone","email","accountId","accountUsername","accountDisplayName","licenseType","licenseTypeCode","licenseTypeLabel","licenseReference","licenseDueDate"))assertThat(driver.get(key).isNull()).isTrue();
        assertThat(driver.get("accountBindingValid").asBoolean()).isFalse();assertThat(driver.get("licenseTypeEnabled").asBoolean()).isFalse();
        Account bound=account("DRIVER",false);driver=patch(admin,driver,fields("accountId",bound.id().toString(),"dutyStatus","ON_DUTY","licenseReference","合成內部證照備註，不應外洩","phone","合成分機","email","待人工確認"));
        JsonNode summary=page(admin.get("/fms/drivers")).get(0);shape(summary,SUMMARY);assertThat(summary.get("accountId").asString()).isEqualTo(bound.id().toString());
        JsonNode option=page(admin.get("/fms/driver-options")).get(0);shape(option,OPTION);
        Browser self=new Browser();login(self,bound);var own=self.get("/fms/drivers/me");response(own,200);shape(data(own),SELF);assertThat(data(own).get("id").asString()).isEqualTo(id(driver));
        assertThat(own.body()).doesNotContain("合成內部證照備註","accountId","accountUsername","accountDisplayName","password","authVersion","licenseReference","version","createdBy");
    }

    @Test void truePartialUpdatesClearOnlyOptionalFieldsAndNeverAllowChangingStableCodeIdentityOrAudit()throws Exception{
        Account bound=account("DRIVER",false);JsonNode original=create(admin,fields("driverCode",code(),"name"," 合成 e\u0301 姓名 ","phone"," 任意非國別格式 ","email","非ASCII待確認","accountId",bound.id().toString(),"licenseType",types.get(0).toString(),"licenseReference","合成識別","licenseDueDate","1500-01-02"));
        assertThat(original.get("name").asString()).isEqualTo("合成 é 姓名");assertThat(original.get("accountBindingValid").asBoolean()).isTrue();
        JsonNode cleared=patch(admin,original,fields("phone",null,"email","\ufeff\u00a0 ","accountId",null,"licenseType",null,"licenseReference",null,"licenseDueDate",null));
        for(String key:List.of("phone","email","accountId","accountUsername","accountDisplayName","licenseType","licenseTypeCode","licenseTypeLabel","licenseReference","licenseDueDate"))assertThat(cleared.get(key).isNull()).isTrue();
        assertThat(cleared.get("driverCode")).isEqualTo(original.get("driverCode"));assertThat(cleared.get("id")).isEqualTo(original.get("id"));assertThat(cleared.get("createdAt")).isEqualTo(original.get("createdAt"));assertThat(cleared.get("createdBy").asString()).isEqualTo(actor.id().toString());assertThat(cleared.get("updatedBy").asString()).isEqualTo(actor.id().toString());
        for(String key:List.of("driverCode","id","createdBy","updatedAt","accountUsername","licenseTypeEnabled","role","driverId"))response(admin.write("PATCH","/fms/drivers/"+id(cleared),fields("version",cleared.get("version").asLong(),key,"client-controlled"),true),400);
        assertThat(data(admin.get("/fms/drivers/"+id(cleared)))).isEqualTo(cleared);assertThat(admin.write("DELETE","/fms/drivers/"+id(cleared),null,true).statusCode()).isEqualTo(405);
    }

    @Test void canonicalUniqueCodeCannotBeReusedByLeftRecordsAndConcurrentCasefoldCreatesHaveOneWinner()throws Exception{
        JsonNode original=create(admin,fields("driverCode","\u001c\ufeff e\u0301-a  b \u202f","name","合成在職"));assertThat(original.get("driverCode").asString()).isEqualTo("É-A  B");
        patch(admin,original,fields("employmentStatus","LEFT","dutyStatus","ON_DUTY"));response(admin.write("POST","/fms/drivers",fields("driverCode","é-a  b","name","合成重複"),true),409);
        String token=admin.csrf();try(var pool=Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(List.<Callable<HttpResponse<String>>>of(()->admin.send("POST","/fms/drivers",json.writeValueAsString(fields("driverCode","ß-race","name","合成甲")),token),()->admin.send("POST","/fms/drivers",json.writeValueAsString(fields("driverCode","SS-RACE","name","合成乙")),token)));
            assertThat(List.of(results.get(0).get().statusCode(),results.get(1).get().statusCode())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(count()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.fms_driver WHERE driver_code='SS-RACE'",Long.class)).isEqualTo(1);
    }

    @Test void exactGregorianLicenseDatesAndBoundedDescriptiveTextAreVerifiedByIndependentPostgresText()throws Exception{
        JsonNode driver=create(admin,fields("driverCode",code(),"name","😀".repeat(200),"phone","😀".repeat(50),"email","😀".repeat(254),"licenseReference","😀".repeat(100),"licenseDueDate","1500-01-02","dutyStatus","ON_DUTY"));
        for(String date:List.of("1500-01-02","0001-01-01","1582-10-10","9999-12-31")){
            if(!date.equals("1500-01-02"))driver=patch(admin,driver,fields("licenseDueDate",date));
            assertThat(driver.get("licenseDueDate").asString()).isEqualTo(date);assertThat(jdbc.queryForObject("SELECT license_due_date::text FROM logistics.fms_driver WHERE id=?",String.class,uuid(driver))).isEqualTo(date);
        }
        assertThat(page(admin.get("/fms/driver-options")).size()).isEqualTo(1);
    }

    @Test void employmentAndDutyCrossCombinationsStayIndependentAndOnlyLeftReinstatementIsDenied()throws Exception{
        List<String> expectedOptions=new ArrayList<>();
        for(String employment:List.of("ACTIVE","INACTIVE","LEFT"))for(String duty:List.of("ON_DUTY","OFF_DUTY")){
            JsonNode driver=create(admin,fields("driverCode",code(),"name","合成行政狀態","employmentStatus",employment,"dutyStatus",duty));
            assertThat(driver.get("employmentStatus").asString()).isEqualTo(employment);assertThat(driver.get("dutyStatus").asString()).isEqualTo(duty);
            if(employment.equals("ACTIVE")&&duty.equals("ON_DUTY"))expectedOptions.add(id(driver));
        }
        List<String> optionIds=new ArrayList<>();for(JsonNode value:page(admin.get("/fms/driver-options")))optionIds.add(id(value));assertThat(optionIds).containsExactlyElementsOf(expectedOptions);
        JsonNode driver=create(admin,minimal());driver=patch(admin,driver,fields("employmentStatus","INACTIVE","dutyStatus","ON_DUTY"));driver=patch(admin,driver,fields("employmentStatus","ACTIVE"));driver=patch(admin,driver,fields("employmentStatus","LEFT"));
        for(String state:List.of("ACTIVE","INACTIVE")){var rejected=admin.write("PATCH","/fms/drivers/"+id(driver),fields("version",driver.get("version").asLong(),"employmentStatus",state,"name","不應部分更新"),true);response(rejected,409);assertThat(data(rejected).has("employmentStatus")).isTrue();assertThat(data(admin.get("/fms/drivers/"+id(driver)))).isEqualTo(driver);}
        JsonNode maintained=patch(admin,driver,fields("employmentStatus","LEFT","name","合成離職維護"));assertThat(maintained.get("dutyStatus").asString()).isEqualTo("ON_DUTY");
    }

    @Test void optionalLicenseReferencesAreCategoryBoundAndArchivedValuesRemainVisibleAndMaintainable()throws Exception{
        JsonNode driver=create(admin,fields("driverCode",code(),"name","合成駕照","licenseType",types.get(0).toString(),"licenseDueDate","0001-01-01","dutyStatus","ON_DUTY"));
        UUID wrong=jdbc.queryForObject("SELECT id FROM logistics.adm_dict_item WHERE code='IMPORT'",UUID.class);response(admin.write("POST","/fms/drivers",fields("driverCode",code(),"name","合成錯分類","licenseType",wrong.toString()),true),400);
        jdbc.update("UPDATE logistics.adm_dict_item SET enabled=false,label='合成封存新標籤',version=version+1 WHERE id=?",types.get(0));
        JsonNode historical=data(admin.get("/fms/drivers/"+id(driver)));assertThat(historical.get("licenseTypeLabel").asString()).isEqualTo("合成封存新標籤");assertThat(historical.get("licenseTypeEnabled").asBoolean()).isFalse();
        JsonNode kept=patch(admin,historical,fields("licenseType",types.get(0).toString(),"phone","合成維護"));response(admin.write("POST","/fms/drivers",fields("driverCode",code(),"name","合成新引用拒絕","licenseType",types.get(0).toString()),true),400);
        jdbc.update("UPDATE logistics.adm_dict_category SET enabled=false,version=version+1 WHERE id=?",category);response(admin.get("/fms/driver-license-types"),200);assertThat(data(admin.get("/fms/driver-license-types")).size()).isZero();
        assertThat(page(admin.get("/fms/driver-options")).get(0).get("licenseTypeEnabled").asBoolean()).isFalse();
        response(admin.write("PATCH","/fms/drivers/"+id(kept),fields("version",kept.get("version").asLong(),"licenseType",types.get(1).toString()),true),400);
        JsonNode cleared=patch(admin,kept,fields("licenseType",null));assertThat(cleared.get("licenseType").isNull()).isTrue();assertThat(cleared.get("licenseTypeEnabled").asBoolean()).isFalse();
    }

    @Test void accountCandidatesIncludeValidTemporaryAndBoundRowsBeforePaginationWithOnlyNarrowPublicFields()throws Exception{
        String prefix="candidate-"+UUID.randomUUID().toString().substring(0,8);List<Account> eligible=new ArrayList<>();
        for(int i=0;i<3;i++)eligible.add(account("DRIVER",false,prefix));eligible.add(account("DRIVER",true,prefix));
        Account disabled=account("DRIVER",false,prefix),expired=account("DRIVER",true,prefix);account("ADMIN",false,prefix);account("DISPATCHER",false,prefix);
        jdbc.update("UPDATE logistics.upms_account SET enabled=false WHERE id=?",disabled.id());jdbc.update("UPDATE logistics.upms_account SET temporary_password_expires_at=? WHERE id=?",java.time.OffsetDateTime.ofInstant(clock.instant().minusSeconds(1),ZoneOffset.UTC),expired.id());
        create(admin,fields("driverCode",code(),"name","合成已綁候選","accountId",eligible.get(0).id().toString()));
        List<String> expected=eligible.stream().map(value->value.id().toString()).sorted(java.util.Comparator.comparing(value->jdbc.queryForObject("SELECT username FROM logistics.upms_account WHERE id=?",String.class,UUID.fromString(value)))).toList();List<String> seen=new ArrayList<>();int bound=0;
        for(int page=0;page<2;page++){
            var reply=admin.get("/fms/driver-account-options?q="+prefix.toUpperCase(java.util.Locale.ROOT)+"&page="+page+"&size=2");response(reply,200);JsonNode result=data(reply);assertThat(result.get("totalElements").asLong()).isEqualTo(4);assertThat(result.get("totalPages").asInt()).isEqualTo(2);assertThat(result.get("content").size()).isEqualTo(2);
            for(JsonNode candidate:result.get("content")){shape(candidate,List.of("id","username","displayName","bound"));seen.add(candidate.get("id").asString());if(candidate.get("bound").asBoolean()){bound++;assertThat(candidate.get("id").asString()).isEqualTo(eligible.get(0).id().toString());}}
        }
        assertThat(seen).containsExactlyElementsOf(expected);assertThat(bound).isEqualTo(1);
        jdbc.update("UPDATE logistics.upms_account SET display_name='合成%_\\字面姓名' WHERE id=?",eligible.get(1).id());assertThat(page(admin.get("/fms/driver-account-options?q="+encode("%_\\"))).size()).isEqualTo(1);
        assertThat(page(admin.get("/fms/driver-account-options?q="+encode("NEVER_MATCH%"))).size()).isZero();
    }

    @Test void bindingsRejectNonDriverDisabledExpiredOrOccupiedTargetsAndUnbindingReleasesOnlyTheRelation()throws Exception{
        Account good=account("DRIVER",false),disabled=account("DRIVER",false),expired=account("DRIVER",true),wrong=account("DISPATCHER",false);
        jdbc.update("UPDATE logistics.upms_account SET enabled=false WHERE id=?",disabled.id());jdbc.update("UPDATE logistics.upms_account SET temporary_password_expires_at=? WHERE id=?",java.time.OffsetDateTime.ofInstant(clock.instant().minusSeconds(1),ZoneOffset.UTC),expired.id());
        for(UUID target:List.of(disabled.id(),expired.id(),wrong.id(),UUID.randomUUID())){var rejected=admin.write("POST","/fms/drivers",fields("driverCode",code(),"name","合成不可綁定","accountId",target.toString()),true);response(rejected,400);assertThat(data(rejected).has("accountId")).isTrue();}
        JsonNode first=create(admin,fields("driverCode",code(),"name","合成綁定","accountId",good.id().toString()));JsonNode second=create(admin,minimal());
        var occupied=admin.write("PATCH","/fms/drivers/"+id(second),fields("version",0,"accountId",good.id().toString(),"name","不應部分寫入"),true);response(occupied,409);assertThat(data(occupied).has("accountId")).isTrue();assertThat(data(admin.get("/fms/drivers/"+id(second)))).isEqualTo(second);
        String accountBefore=accountDigest(good.id());JsonNode unbound=patch(admin,first,fields("accountId",null));assertThat(unbound.get("accountBindingValid").asBoolean()).isFalse();assertThat(accountDigest(good.id())).isEqualTo(accountBefore);
        JsonNode rebound=patch(admin,second,fields("accountId",good.id().toString()));assertThat(rebound.get("accountBindingValid").asBoolean()).isTrue();assertThat(accountDigest(good.id())).isEqualTo(accountBefore);
    }

    @Test void validTemporaryDriverMayBindButSelfRemainsBlockedUntilNormalPasswordCompletion()throws Exception{
        Account temporary=account("DRIVER",true);JsonNode driver=create(admin,fields("driverCode",code(),"name","合成初始密碼司機","accountId",temporary.id().toString()));assertThat(driver.get("accountBindingValid").asBoolean()).isTrue();
        Browser self=new Browser();login(self,temporary);response(self.get("/fms/drivers/me"),403);
        String replacement=UpmsTestData.password();response(self.write("POST","/auth/password",fields("currentPassword",temporary.password(),"newPassword",replacement),true),200);
        login(self,new Account(temporary.id(),temporary.username(),replacement));response(self.get("/fms/drivers/me"),200);shape(data(self.get("/fms/drivers/me")),SELF);
    }

    @Test void selfIsAlwaysCurrentActorBindingAndNoIdentifierCanReadAnotherPersonOrBroadenOtherModules()throws Exception{
        Account first=account("DRIVER",false),second=account("DRIVER",false);JsonNode a=create(admin,fields("driverCode",code(),"name","合成本人甲","accountId",first.id().toString(),"licenseReference","合成不得外洩甲"));JsonNode b=create(admin,fields("driverCode",code(),"name","合成本人乙","accountId",second.id().toString(),"licenseReference","合成不得外洩乙"));
        Browser self=new Browser();login(self,first);var reply=self.get("/fms/drivers/me");response(reply,200);shape(data(reply),SELF);assertThat(data(reply).get("id").asString()).isEqualTo(id(a));assertThat(reply.body()).doesNotContain("合成本人乙","合成不得外洩","licenseReference","accountId","version","updatedAt");
        for(String query:List.of("id="+id(b),"driverId="+id(b),"accountId="+second.id(),"q=anything","page=0"))response(self.get("/fms/drivers/me?"+query),400);
        for(String path:List.of("/fms/drivers/"+id(a),"/fms/drivers/"+id(b),"/fms/vehicles","/crm/customers","/orders","/upms/accounts","/adm/categories")){var denied=self.get(path);assertThat(denied.statusCode()).isEqualTo(403);}
        a=patch(admin,a,fields("employmentStatus","LEFT","dutyStatus","ON_DUTY"));response(self.get("/fms/drivers/me"),200);assertThat(data(self.get("/fms/drivers/me")).get("employmentStatus").asString()).isEqualTo("LEFT");
        patch(admin,a,fields("accountId",null));response(self.get("/fms/drivers/me"),404);
        b=patch(admin,b,fields("accountId",first.id().toString()));var rebound=self.get("/fms/drivers/me");response(rebound,200);assertThat(data(rebound).get("id").asString()).isEqualTo(id(b));
        Browser previous=new Browser();login(previous,second);response(previous.get("/fms/drivers/me"),404);
    }

    @Test void roleDisableAndRecoveryRevocationInvalidateSelfWithoutSilentlyUnlinkingOrBlockingUnrelatedMaintenance()throws Exception{
        for(String change:List.of("role","disable","recovery")){
            Account target=account("DRIVER",false);JsonNode driver=create(admin,fields("driverCode",code(),"name","合成外部身分異動","accountId",target.id().toString()));Browser self=new Browser();login(self,target);response(self.get("/fms/drivers/me"),200);
            if(change.equals("recovery"))jdbc.update("UPDATE logistics.upms_account SET auth_version=auth_version+1,must_change_password=true,temporary_password_expires_at=? WHERE id=?",java.time.OffsetDateTime.ofInstant(clock.instant().plusSeconds(900),ZoneOffset.UTC),target.id());
            else response(changeAccount(target,change.equals("role")?"DISPATCHER":"DRIVER",!change.equals("disable")),200);
            response(self.get("/fms/drivers/me"),401);JsonNode retained=data(admin.get("/fms/drivers/"+id(driver)));assertThat(retained.get("accountId").asString()).isEqualTo(target.id().toString());
            assertThat(retained.get("accountBindingValid").asBoolean()).isEqualTo(change.equals("recovery"));
            JsonNode kept=patch(admin,retained,fields("accountId",target.id().toString(),"phone","合成原關聯維護"));assertThat(kept.get("accountId")).isEqualTo(retained.get("accountId"));patch(admin,kept,fields("accountId",null));
        }
    }

    @Test void literalSearchAndFixedPagingNeverSearchPrivateLicenseOrOtherAccountInformation()throws Exception{
        for(String code:List.of("SYN-C","SYN-A","SYN-B","SYN-%_\\","straße"))create(admin,fields("driverCode",code,"name",code.contains("%")?" e\u0301Name%_\\ ":"合成一般姓名","licenseType",types.get(0).toString(),"licenseReference","private-license-search-only","phone","合成聯絡","email","描述性電郵"));
        List<String> expected=jdbc.queryForList("SELECT id::text FROM logistics.fms_driver ORDER BY driver_code,id",String.class),seen=new ArrayList<>();
        for(int page=0;page<3;page++){var result=admin.get("/fms/drivers?page="+page+"&size=2");response(result,200);assertThat(data(result).get("totalElements").asLong()).isEqualTo(5);for(JsonNode value:data(result).get("content"))seen.add(id(value));}
        assertThat(seen).containsExactlyElementsOf(expected);assertThat(page(admin.get("/fms/drivers?q="+encode("%_\\"))).size()).isEqualTo(1);assertThat(page(admin.get("/fms/drivers?q="+encode("straße"))).get(0).get("driverCode").asString()).isEqualTo("STRASSE");
        assertThat(page(admin.get("/fms/drivers?q="+encode(" e\u0301NAME%_\\ "))).size()).isEqualTo(1);assertThat(page(admin.get("/fms/drivers?q=private-license-search-only")).size()).isZero();assertThat(page(admin.get("/fms/drivers?q="+actor.username())).size()).isZero();
        assertThat(page(admin.get("/fms/drivers?licenseType="+types.get(1))).size()).isZero();assertThat(page(admin.get("/fms/drivers?employmentStatus=INACTIVE")).size()).isZero();assertThat(page(admin.get("/fms/drivers?dutyStatus=ON_DUTY")).size()).isZero();assertThat(page(admin.get("/fms/drivers?page=100000&size=100")).size()).isZero();
    }

    @Test void strictUnknownTypesFieldsQueriesAndBoundsRejectWithoutAnyPartialMasterWrites()throws Exception{
        for(String key:List.of("id","version","createdAt","createdBy","updatedAt","updatedBy","accountUsername","accountDisplayName","accountBindingValid","licenseTypeCode","licenseTypeLabel","licenseTypeEnabled","driverId","assignmentId","password","role")){var request=minimal();request.put(key,"not-client-owned");response(admin.write("POST","/fms/drivers",request,true),400);}
        for(Map<String,Object> invalid:List.of(fields("driverCode",null),fields("driverCode","\ufeff "),fields("driverCode","x".repeat(41)),fields("driverCode","x\u0000y"),fields("driverCode",1),fields("name",null),fields("name","\ufeff "),fields("name","x".repeat(201)),fields("phone","x".repeat(51)),fields("email","x".repeat(255)),fields("licenseReference","x".repeat(101)),fields("licenseDueDate","0000-01-01"),fields("licenseDueDate","1900-02-29"),fields("accountId","1-1-1-1-1"),fields("licenseType",UUID.randomUUID().toString()),fields("employmentStatus","IN_USE"),fields("employmentStatus",null),fields("dutyStatus",null))){var request=minimal();request.putAll(invalid);response(admin.write("POST","/fms/drivers",request,true),400);}
        response(admin.raw("POST","/fms/drivers","{\"name\":\"A\",\"name\":\"B\"}",true),400);response(admin.raw("POST","/fms/drivers","{} {}",true),400);response(admin.raw("POST","/fms/drivers","{\"driverCode\":\"\\ud800\",\"name\":\"合成\"}",true),400);assertThat(count()).isZero();
        JsonNode driver=create(admin,minimal());String path="/fms/drivers/"+id(driver);
        for(String query:List.of("page=-1","page=100001","page=0.5","size=0","size=101","q="+"x".repeat(101),"page=0&page=0","sort=name,asc","driverId="+id(driver),"licenseType=1-1-1-1-1","employmentStatus=UNKNOWN","dutyStatus=IN_USE"))response(admin.get("/fms/drivers?"+query),400);
        for(String query:List.of("employmentStatus=ACTIVE","dutyStatus=ON_DUTY","sort=driverCode,asc","accountId=x"))response(admin.get("/fms/driver-options?"+query),400);
        for(String query:List.of("driverId="+id(driver),"currentDriverId="+id(driver),"licenseType="+types.get(0),"sort=username,asc"))response(admin.get("/fms/driver-account-options?"+query),400);
        for(String exact:List.of(path,"/fms/driver-license-types"))response(admin.get(exact+"?unknown=1"),400);
        response(admin.write("POST","/fms/drivers?unknown=1",minimal(),true),400);response(admin.write("PATCH",path+"?unknown=1",fields("version",0,"name","合成拒絕"),true),400);
        for(String key:List.of("name","employmentStatus","dutyStatus"))response(admin.write("PATCH",path,fields("version",0,key,null),true),400);
        response(admin.write("PATCH",path,fields("version",0),true),400);response(admin.get("/fms/drivers/1-1-1-1-1"),400);response(admin.get("/fms/drivers/"+UUID.randomUUID()),404);assertThat(data(admin.get(path))).isEqualTo(driver);
    }

    @Test void concurrentUniqueAccountBindingHasExactlyOneWinnerAndDoesNotChangeUpmsAccountState()throws Exception{
        Account target=account("DRIVER",false);String before=accountDigest(target.id()),token=admin.csrf();
        try(var pool=Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(List.<Callable<HttpResponse<String>>>of(()->admin.send("POST","/fms/drivers",json.writeValueAsString(fields("driverCode",code(),"name","合成甲綁定","accountId",target.id().toString())),token),()->admin.send("POST","/fms/drivers",json.writeValueAsString(fields("driverCode",code(),"name","合成乙綁定","accountId",target.id().toString())),token)));
            assertThat(List.of(results.get(0).get().statusCode(),results.get(1).get().statusCode())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(count()).isEqualTo(1);assertThat(accountDigest(target.id())).isEqualTo(before);
    }

    @Test void fixedClockNoopAndConcurrentVersionsIncrementExactlyOnceAndMaximumVersionCannotOverflow()throws Exception{
        JsonNode driver=create(admin,minimal());JsonNode same=patch(admin,driver,fields("name",driver.get("name").asString()));assertThat(java.time.Instant.parse(same.get("updatedAt").asString())).isAfter(java.time.Instant.parse(driver.get("updatedAt").asString()));
        String token=admin.csrf(),path="/fms/drivers/"+id(driver);try(var pool=Executors.newFixedThreadPool(2)){
            var results=pool.invokeAll(List.<Callable<HttpResponse<String>>>of(()->admin.send("PATCH",path,json.writeValueAsString(fields("version",1,"name","合成甲")),token),()->admin.send("PATCH",path,json.writeValueAsString(fields("version",1,"name","合成乙")),token)));
            assertThat(List.of(results.get(0).get().statusCode(),results.get(1).get().statusCode())).containsExactlyInAnyOrder(200,409);
        }
        JsonNode latest=data(admin.get(path));assertThat(latest.get("version").asLong()).isEqualTo(2);response(admin.write("PATCH",path,fields("version",1,"name","合成舊版拒絕"),true),409);assertThat(data(admin.get(path))).isEqualTo(latest);
        jdbc.update("UPDATE logistics.fms_driver SET version=9007199254740991 WHERE id=?",uuid(driver));JsonNode exhausted=data(admin.get(path));response(admin.write("PATCH",path,fields("version",9007199254740991L,"name","合成溢位拒絕"),true),409);assertThat(data(admin.get(path))).isEqualTo(exhausted);
    }

    @Test void invalidNewLicenseOrAccountReferenceRollsBackNameAndExistingBindingInTheSameTransaction()throws Exception{
        Account first=account("DRIVER",false),other=account("DRIVER",false);JsonNode driver=create(admin,fields("driverCode",code(),"name","合成原始","accountId",first.id().toString(),"licenseType",types.get(0).toString()));
        jdbc.update("UPDATE logistics.adm_dict_item SET enabled=false WHERE id=?",types.get(1));
        response(admin.write("PATCH","/fms/drivers/"+id(driver),fields("version",0,"name","不應部分存入","accountId",other.id().toString(),"licenseType",types.get(1).toString()),true),400);assertThat(data(admin.get("/fms/drivers/"+id(driver)))).isEqualTo(driver);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.fms_driver WHERE account_id=?",Long.class,other.id())).isZero();
        jdbc.update("UPDATE logistics.upms_account SET enabled=false WHERE id=?",other.id());response(admin.write("PATCH","/fms/drivers/"+id(driver),fields("version",0,"name","不應部分存入","accountId",other.id().toString()),true),400);assertThat(data(admin.get("/fms/drivers/"+id(driver)))).isEqualTo(driver);
    }

    @Test void revokedManagerWaitingForCanonicalAuthorizationCannotUseItsEarlierRoleOrPartiallyWrite()throws Exception{
        JsonNode driver=create(admin,minimal());String token=admin.csrf();try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
            held.setAutoCommit(false);canonical(held);var pending=pool.submit(()->admin.send("PATCH","/fms/drivers/"+id(driver),json.writeValueAsString(fields("version",0,"name","不應部分寫入")),token));awaitDatabaseLock("upms_lock");
            try(var statement=held.prepareStatement("UPDATE logistics.upms_account SET auth_version=auth_version+1,role='DRIVER' WHERE id=?")){statement.setObject(1,actor.id());statement.executeUpdate();}held.commit();response(pending.get(15,TimeUnit.SECONDS),401);
        }
        assertThat(jdbc.queryForObject("SELECT name FROM logistics.fms_driver WHERE id=?",String.class,uuid(driver))).isEqualTo(driver.get("name").asString());assertThat(jdbc.queryForObject("SELECT version FROM logistics.fms_driver WHERE id=?",Long.class,uuid(driver))).isZero();
    }

    @Test void targetRoleChangeWinningCanonicalLockRejectsBindingUsingItsFreshCommittedRole()throws Exception{
        Account target=account("DRIVER",false);String token=admin.csrf();try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
            held.setAutoCommit(false);canonical(held);try(var statement=held.prepareStatement("UPDATE logistics.upms_account SET role='DISPATCHER',auth_version=auth_version+1 WHERE id=?")){statement.setObject(1,target.id());statement.executeUpdate();}
            var pending=pool.submit(()->admin.send("POST","/fms/drivers",json.writeValueAsString(fields("driverCode",code(),"name","合成過期角色引用","accountId",target.id().toString())),token));awaitDatabaseLock("upms_lock");held.commit();response(pending.get(15,TimeUnit.SECONDS),400);
        }
        assertThat(count()).isZero();
    }

    @Test void bindingWinningCanonicalLockCommitsBeforeLaterRealUpmsRoleChangeAndThenFlagsTheRetainedBinding()throws Exception{
        Account target=account("DRIVER",false);Browser self=new Browser();login(self,target);String token=admin.csrf();createPauseTrigger();var pool=Executors.newFixedThreadPool(2);
        try(var gate=dataSource.getConnection()){
            advisory(gate,true);try{
                var create=pool.submit(()->admin.send("POST","/fms/drivers",json.writeValueAsString(fields("driverCode",code(),"name","合成先綁定","accountId",target.id().toString())),token));awaitDatabaseLock("fms_driver");
                var changed=pool.submit(()->admin.send("PATCH","/upms/accounts/"+target.id(),json.writeValueAsString(accountChange(target,"DISPATCHER",true)),token));awaitDatabaseLock("upms_lock");assertThat(changed.isDone()).isFalse();advisory(gate,false);
                var created=create.get(15,TimeUnit.SECONDS);response(created,201);response(changed.get(15,TimeUnit.SECONDS),200);
                JsonNode retained=data(admin.get("/fms/drivers/"+id(data(created))));assertThat(retained.get("accountId").asString()).isEqualTo(target.id().toString());assertThat(retained.get("accountBindingValid").asBoolean()).isFalse();response(self.get("/fms/drivers/me"),401);
            }finally{advisory(gate,false);}
        }finally{dropPauseTrigger();pool.shutdownNow();}
    }

    @Test void unbindWinningCanonicalLockPreventsAQueuedSelfReadFromReturningTheOldAssociation()throws Exception{
        Account target=account("DRIVER",false);JsonNode driver=create(admin,fields("driverCode",code(),"name","合成即將解除","accountId",target.id().toString()));Browser self=new Browser();login(self,target);
        try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
            held.setAutoCommit(false);canonical(held);try(var statement=held.prepareStatement("UPDATE logistics.fms_driver SET account_id=NULL,version=version+1 WHERE id=?")){statement.setObject(1,uuid(driver));statement.executeUpdate();}
            var pending=pool.submit(()->self.get("/fms/drivers/me"));awaitDatabaseLock("upms_lock");held.commit();var reply=pending.get(15,TimeUnit.SECONDS);response(reply,404);assertThat(reply.body()).doesNotContain("合成即將解除","licenseReference","accountId");
        }
    }

    @Test void selfReadWinningCanonicalLockKeepsUnbindAndRoleChangeWaitingUntilItsOwnFreshSnapshotCompletes()throws Exception{
        for(boolean unbind:List.of(true,false)){
            Account target=account("DRIVER",false);JsonNode driver=create(admin,fields("driverCode",code(),"name","合成先讀本人","accountId",target.id().toString()));Browser self=new Browser();login(self,target);String token=admin.csrf();
            try(var pool=Executors.newFixedThreadPool(2);var source=dataSource.getConnection()){
                source.setAutoCommit(false);try(var statement=source.prepareStatement("SELECT id FROM logistics.fms_driver WHERE id=? FOR UPDATE")){statement.setObject(1,uuid(driver));statement.executeQuery().close();}
                var reading=pool.submit(()->self.get("/fms/drivers/me"));awaitDatabaseLock("fms_driver");
                var writing=pool.submit(()->unbind?admin.send("PATCH","/fms/drivers/"+id(driver),json.writeValueAsString(fields("version",0,"accountId",null)),token):admin.send("PATCH","/upms/accounts/"+target.id(),json.writeValueAsString(accountChange(target,"DISPATCHER",true)),token));awaitDatabaseLock("upms_lock");assertThat(writing.isDone()).isFalse();source.commit();
                var before=reading.get(15,TimeUnit.SECONDS);response(before,200);shape(data(before),SELF);assertThat(data(before).get("id").asString()).isEqualTo(id(driver));response(writing.get(15,TimeUnit.SECONDS),200);response(self.get("/fms/drivers/me"),unbind?404:401);
            }
        }
    }

    @Test void activeLicenseCategoryAndItemDisableRacesSerializeWithDriverCreation()throws Exception{
        String token=admin.csrf();for(String table:List.of("adm_dict_category","adm_dict_item")){
            UUID target=table.equals("adm_dict_category")?category:types.get(0);
            try(var pool=Executors.newSingleThreadExecutor();var held=dataSource.getConnection()){
                held.setAutoCommit(false);try(var statement=held.prepareStatement("UPDATE logistics."+table+" SET enabled=false,version=version+1 WHERE id=?")){statement.setObject(1,target);statement.executeUpdate();}
                var pending=pool.submit(()->admin.send("POST","/fms/drivers",json.writeValueAsString(fields("driverCode",code(),"name","合成分類競爭","licenseType",types.get(0).toString())),token));awaitDatabaseLock(table);held.commit();response(pending.get(15,TimeUnit.SECONDS),400);
            }
            assertThat(count()).isZero();jdbc.update("UPDATE logistics."+table+" SET enabled=true,version=version+1 WHERE id=?",target);
        }
    }

    @Test void publicAccountPortIsMinimalMandatoryFreshAndTemporaryAwareWithoutManagedAccountLeakage()throws Exception{
        Account target=account("DRIVER",false),temporary=account("DRIVER",true);
        assertThat(accounts.findCurrent(null)).isEmpty();assertThat(accounts.findCurrent(UUID.randomUUID())).isEmpty();
        assertThatThrownBy(()->accounts.requireEligibleForBinding(target.id())).isInstanceOf(IllegalTransactionStateException.class);
        var readonly=new TransactionTemplate(transactionManager);readonly.setReadOnly(true);assertThatThrownBy(()->readonly.execute(status->accounts.requireEligibleForBinding(target.id()))).isInstanceOf(IllegalTransactionStateException.class);
        var transaction=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->transaction.execute(status->{
            authorization.requireDriverManagerForWrite(principal(actor));entityManager.createQuery("select a from AccountEntity a where a.id=:id").setParameter("id",target.id()).getSingleResult();
            external("UPDATE logistics.upms_account SET display_name='合成外部最新顯示名稱',version=version+1 WHERE id=?",target.id());
            assertThat(accounts.findCurrent(target.id()).orElseThrow().displayName()).isEqualTo("合成外部最新顯示名稱");
            assertThat(accounts.candidates(0,100,target.username()).getContent().get(0).displayName()).isEqualTo("合成外部最新顯示名稱");
            external("UPDATE logistics.upms_account SET role='DISPATCHER',version=version+1 WHERE id=?",target.id());
            assertThat(accounts.findCurrent(target.id()).orElseThrow().bindingValid()).isFalse();
            return accounts.requireEligibleForBinding(target.id());
        })).isInstanceOf(UpmsException.class);
        assertThat(transaction.execute(status->{authorization.requireDriverManagerForWrite(principal(actor));return accounts.requireEligibleForBinding(temporary.id());}).bindingValid()).isTrue();
        clock.advance(Duration.ofMinutes(15));assertThat(accounts.findCurrent(temporary.id()).orElseThrow().bindingValid()).isFalse();
        assertThatThrownBy(()->transaction.execute(status->{authorization.requireDriverManagerForWrite(principal(actor));return accounts.requireEligibleForBinding(temporary.id());})).isInstanceOf(UpmsException.class);
    }

    @Test void accountReferenceGuardKeepsItsSourceRowLockedUntilTheCallerTransactionCommits()throws Exception{
        Account target=account("DRIVER",false);var transaction=new TransactionTemplate(transactionManager);var pool=Executors.newSingleThreadExecutor();java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>> changed=new java.util.concurrent.atomic.AtomicReference<>();
        try{
            var snapshot=transaction.execute(status->{authorization.requireDriverManagerForWrite(principal(actor));var selected=accounts.requireEligibleForBinding(target.id());changed.set(pool.submit(()->jdbc.update("UPDATE logistics.upms_account SET enabled=false,version=version+1 WHERE id=?",target.id())));awaitDatabaseLock("upms_account");assertThat(changed.get().isDone()).isFalse();return selected;});
            assertThat(changed.get().get(15,TimeUnit.SECONDS)).isEqualTo(1);assertThat(snapshot.bindingValid()).isTrue();assertThat(accounts.findCurrent(target.id()).orElseThrow().bindingValid()).isFalse();
        }finally{pool.shutdownNow();}
    }

    @Test void publicDriverSourceIsImmutableMandatoryAndOnlyChecksActiveOnDutyWithFreshCachedState()throws Exception{
        JsonNode driver=create(admin,fields("driverCode",code(),"name","合成無帳號行政來源","dutyStatus","ON_DUTY","licenseType",types.get(0).toString(),"licenseDueDate","0001-01-01","licenseReference","合成不得進可信快照"));UUID id=uuid(driver);
        assertThat(queries.findCurrent(null)).isEmpty();assertThat(queries.findCurrent(UUID.randomUUID())).isEmpty();assertThatThrownBy(()->queries.requireAdministrativelyAvailable(id)).isInstanceOf(IllegalTransactionStateException.class);
        var readonly=new TransactionTemplate(transactionManager);readonly.setReadOnly(true);assertThatThrownBy(()->readonly.execute(status->queries.requireAdministrativelyAvailable(id))).isInstanceOf(IllegalTransactionStateException.class);
        var transaction=new TransactionTemplate(transactionManager);assertThatThrownBy(()->transaction.execute(status->queries.requireAdministrativelyAvailable(UUID.randomUUID()))).isInstanceOfSatisfying(UpmsException.class,error->assertThat(error.status()).isEqualTo(404));
        jdbc.update("UPDATE logistics.adm_dict_item SET enabled=false WHERE id=?",types.get(0));
        DriverQueries.Snapshot old=transaction.execute(status->{
            authorization.requireDriverManagerForWrite(principal(actor));var before=queries.findCurrent(id).orElseThrow();
            external("UPDATE logistics.fms_driver SET name='合成最新來源',version=version+1 WHERE id=?",id);external("UPDATE logistics.adm_dict_item SET label='合成最新歷史類別',version=version+1 WHERE id=?",types.get(0));
            var fresh=queries.requireAdministrativelyAvailable(id);assertThat(fresh.name()).isEqualTo("合成最新來源");assertThat(fresh.version()).isEqualTo(1);assertThat(fresh.licenseTypeEnabled()).isFalse();assertThat(fresh.licenseTypeLabel()).isEqualTo("合成最新歷史類別");assertThat(before.name()).isEqualTo("合成無帳號行政來源");return before;
        });
        for(String[] state:List.of(new String[]{"ACTIVE","OFF_DUTY"},new String[]{"INACTIVE","ON_DUTY"},new String[]{"LEFT","ON_DUTY"})){
            jdbc.update("UPDATE logistics.fms_driver SET employment_status=?,duty_status=?,version=version+1 WHERE id=?",state[0],state[1],id);
            assertThat(queries.findCurrent(id).orElseThrow().employmentStatus().name()).isEqualTo(state[0]);
            assertThatThrownBy(()->transaction.execute(status->{authorization.requireDriverManagerForWrite(principal(actor));queries.findCurrent(id);return queries.requireAdministrativelyAvailable(id);})).isInstanceOfSatisfying(UpmsException.class,error->{assertThat(error.status()).isEqualTo(400);assertThat(((Map<?,?>)error.data()).containsKey("driverId")).isTrue();});
        }
        assertThat(old.employmentStatus()).isEqualTo(EmploymentStatus.ACTIVE);assertThat(old.dutyStatus()).isEqualTo(DutyStatus.ON_DUTY);
        assertThat(java.util.Arrays.stream(DriverQueries.Snapshot.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList()).doesNotContain("accountId","licenseReference","createdBy","authVersion","password");
    }

    @Test void publicDriverReferenceLockMakesStatusChangesWaitAndTheReturnedCopyStaysUnchanged()throws Exception{
        JsonNode driver=create(admin,fields("driverCode",code(),"name","合成鎖來源","dutyStatus","ON_DUTY"));UUID id=uuid(driver);var transaction=new TransactionTemplate(transactionManager);var pool=Executors.newSingleThreadExecutor();java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>> changed=new java.util.concurrent.atomic.AtomicReference<>();
        try{
            var snapshot=transaction.execute(status->{authorization.requireDriverManagerForWrite(principal(actor));var selected=queries.requireAdministrativelyAvailable(id);changed.set(pool.submit(()->jdbc.update("UPDATE logistics.fms_driver SET duty_status='OFF_DUTY',name='合成提交後變更',version=version+1 WHERE id=?",id)));awaitDatabaseLock("fms_driver");assertThat(changed.get().isDone()).isFalse();return selected;});
            assertThat(changed.get().get(15,TimeUnit.SECONDS)).isEqualTo(1);assertThat(snapshot.dutyStatus()).isEqualTo(DutyStatus.ON_DUTY);assertThat(snapshot.name()).isEqualTo("合成鎖來源");assertThat(queries.findCurrent(id).orElseThrow().dutyStatus()).isEqualTo(DutyStatus.OFF_DUTY);
        }finally{pool.shutdownNow();}
    }

    @Test void databaseConstraintsKeepCodeAndBindingUniqueAndAllSourceAndAuditForeignKeysValid()throws Exception{
        Account target=account("DRIVER",false);JsonNode driver=create(admin,fields("driverCode",code(),"name","合成資料庫防護","accountId",target.id().toString(),"licenseType",types.get(0).toString()));UUID id=uuid(driver);
        for(String assignment:List.of("driver_code=NULL","name=NULL","employment_status='IN_USE'","duty_status='AVAILABLE'","version=-1","version=9007199254740992","license_due_date=DATE '0001-01-01 BC'","license_due_date=DATE '10000-01-01'"))assertThatThrownBy(()->jdbc.update("UPDATE logistics.fms_driver SET "+assignment+" WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        for(String field:List.of("account_id","license_type_id","created_by","updated_by"))assertThatThrownBy(()->jdbc.update("UPDATE logistics.fms_driver SET "+field+"=? WHERE id=?",UUID.randomUUID(),id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO logistics.fms_driver(id,driver_code,name,employment_status,duty_status,version,created_at,created_by,updated_at,updated_by) SELECT ?,driver_code,name,employment_status,duty_status,0,created_at,created_by,updated_at,updated_by FROM logistics.fms_driver WHERE id=?",UUID.randomUUID(),id)).isInstanceOf(DataIntegrityViolationException.class);
        JsonNode other=create(admin,minimal());assertThatThrownBy(()->jdbc.update("UPDATE logistics.fms_driver SET account_id=? WHERE id=?",target.id(),uuid(other))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM logistics.upms_account WHERE id=?",target.id())).isInstanceOf(DataIntegrityViolationException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM logistics.adm_dict_item WHERE id=?",types.get(0))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(data(admin.get("/fms/drivers/"+id(driver)))).isEqualTo(driver);
    }

    @Test void failedStorageRollsBackBindingStatusFieldsVersionAndAuditWithoutLeakingSqlOrPrivateReference()throws Exception{
        Account original=account("DRIVER",false),replacement=account("DRIVER",false);JsonNode driver=create(admin,fields("driverCode",code(),"name","合成原始","accountId",original.id().toString()));String path="/fms/drivers/"+id(driver);
        jdbc.execute("CREATE FUNCTION logistics.test_reject_driver_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic driver storage failure'; END $$");jdbc.execute("CREATE TRIGGER test_reject_driver_change BEFORE UPDATE ON logistics.fms_driver FOR EACH ROW EXECUTE FUNCTION logistics.test_reject_driver_change()");
        try{var failed=admin.write("PATCH",path,fields("version",0,"accountId",replacement.id().toString(),"name","不應部分存入","employmentStatus","LEFT","licenseReference","不得外洩合成備註"),true);assertThat(failed.statusCode()).isBetween(500,599);assertThat(failed.body()).doesNotContain("synthetic driver storage failure","不得外洩合成備註","UPDATE logistics","Hibernate","SQLException");}
        finally{jdbc.execute("DROP TRIGGER test_reject_driver_change ON logistics.fms_driver");jdbc.execute("DROP FUNCTION logistics.test_reject_driver_change()");}
        assertThat(data(admin.get(path))).isEqualTo(driver);assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.fms_driver WHERE account_id=?",Long.class,replacement.id())).isZero();
    }

    private Map<String,Object> minimal(){return fields("driverCode",code(),"name","合成司機主檔");}
    private String code(){return "SYN-"+UUID.randomUUID().toString().substring(0,12).toUpperCase(java.util.Locale.ROOT);}
    private static Map<String,Object> fields(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private String id(JsonNode value){return value.get("id").asString();}
    private UUID uuid(JsonNode value){return UUID.fromString(id(value));}
    private long count(){return jdbc.queryForObject("SELECT count(*) FROM logistics.fms_driver",Long.class);}
    private JsonNode body(HttpResponse<String> response){return json.readTree(response.body());}
    private JsonNode data(HttpResponse<String> response){return body(response).get("data");}
    private JsonNode page(HttpResponse<String> response){response(response,200);JsonNode value=data(response);shape(value,List.of("content","page","size","totalElements","totalPages"));return value.get("content");}
    private void response(HttpResponse<String> reply,int status){assertThat(reply.statusCode()).as("FMS HTTP status").isEqualTo(status);JsonNode value=body(reply);shape(value,List.of("code","message","data","timestamp"));assertThat(value.get("code").asString()).isEqualTo(status<400?"0000":"E0"+status);assertThat(java.time.OffsetDateTime.parse(value.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));}
    private void shape(JsonNode value,List<String> fields){assertThat(value.propertyNames()).containsExactlyInAnyOrderElementsOf(fields);}
    private JsonNode create(Browser browser,Map<String,Object> request)throws Exception{var reply=browser.write("POST","/fms/drivers",request,true);response(reply,201);JsonNode driver=data(reply);shape(driver,FULL);return driver;}
    private JsonNode patch(Browser browser,JsonNode previous,Map<String,Object> changes)throws Exception{var request=fields("version",previous.get("version").asLong());request.putAll(changes);var reply=browser.write("PATCH","/fms/drivers/"+id(previous),request,true);response(reply,200);JsonNode driver=data(reply);shape(driver,FULL);assertThat(driver.get("version").asLong()).isEqualTo(previous.get("version").asLong()+1);return driver;}
    private UUID insertType(UUID category,String code,String label,int order){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO logistics.adm_dict_item(id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at) VALUES (?,?,?,?,true,?,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,category,code,label,order);return id;}
    private Account account(String role,boolean temporary)throws Exception{return account(role,temporary,"driver-test");}
    private Account account(String role,boolean temporary,String prefix)throws Exception{String username=prefix+"-"+UUID.randomUUID().toString().substring(0,12),password=UpmsTestData.password();try(var connection=dataSource.getConnection()){return new Account(UpmsTestData.insert(connection,encoder,username,role,password,temporary,clock.instant()),username,password);}}
    private void login(Browser browser,Account account)throws Exception{response(browser.write("POST","/auth/login",fields("username",account.username(),"password",account.password()),true),200);}
    private SessionPrincipal principal(Account account){return new SessionPrincipal(account.id(),0,clock.instant(),clock.instant());}
    private String accountDigest(UUID id){return jdbc.queryForObject("SELECT md5(row_to_json(a)::text) FROM logistics.upms_account a WHERE id=?",String.class,id);}
    private Map<String,Object> accountChange(Account account,String role,boolean enabled){return fields("displayName","合成測試帳號","role",role,"enabled",enabled,"version",jdbc.queryForObject("SELECT version FROM logistics.upms_account WHERE id=?",Long.class,account.id()));}
    private HttpResponse<String> changeAccount(Account account,String role,boolean enabled)throws Exception{return admin.write("PATCH","/upms/accounts/"+account.id(),accountChange(account,role,enabled),true);}
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
    private void awaitDatabaseLock(String table){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);try(var observer=dataSource.getConnection();var statement=observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")){assertThat(observer.getAutoCommit()).isTrue();statement.setString(1,"%"+table+"%");while(System.nanoTime()<deadline){try(var result=statement.executeQuery()){result.next();if(result.getLong(1)>0)return;}Thread.sleep(20);}}catch(Exception failure){throw new IllegalStateException("Could not observe isolated driver source lock",failure);}throw new AssertionError("Expected real database lock for "+table);}
    private void canonical(java.sql.Connection connection)throws java.sql.SQLException{try(var statement=connection.createStatement()){statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();}}
    private void createPauseTrigger(){jdbc.execute("CREATE FUNCTION logistics.test_pause_driver_create() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(202603,2); RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER test_pause_driver_create BEFORE INSERT ON logistics.fms_driver FOR EACH ROW EXECUTE FUNCTION logistics.test_pause_driver_create()");}
    private void dropPauseTrigger(){jdbc.execute("DROP TRIGGER test_pause_driver_create ON logistics.fms_driver");jdbc.execute("DROP FUNCTION logistics.test_pause_driver_create()");}
    private void advisory(java.sql.Connection connection,boolean acquire)throws java.sql.SQLException{try(var statement=connection.createStatement()){statement.execute("SELECT pg_advisory_"+(acquire?"lock":"unlock")+"(202603,2)");}}
}
