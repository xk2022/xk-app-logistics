package com.xksystem.logistics.fms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.upms.UpmsException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

/** Driver-only wire boundaries; real session authorization and source locks live in FmsDriverIT. */
class FmsDriverTest {
    @Test void stableDriverCodeCanonicalizesUnicodeBeforeBoundsAndRejectsControlsOrBrokenSurrogates() {
        assertThat(DriverValidation.driverCode("\u001c\ufeff e\u0301-a  b \u202f\u001f")).isEqualTo("É-A  B");
        assertThat(DriverValidation.driverCode("ß".repeat(20))).isEqualTo("SS".repeat(20));
        assertThat(DriverValidation.driverCode(" ".repeat(200)+"a".repeat(40)+"\ufeff")).isEqualTo("A".repeat(40));
        assertThat(DriverValidation.driverCode("😀".repeat(40))).isEqualTo("😀".repeat(40));
        for(String invalid:List.of(""," \ufeff\u00a0","ß".repeat(20)+"a","😀".repeat(41),"A\u0000B","A\u001cB","A\nB","\ud800","\udc00"))
            assertThatThrownBy(()->DriverValidation.driverCode(invalid)).isInstanceOfSatisfying(UpmsException.class,error->assertThat(((Map<?,?>)error.data()).containsKey("driverCode")).isTrue());
    }

    @Test void descriptiveContactFieldsHaveUnicodeBoundsWithoutInventedPhoneOrEmailPolicies() {
        assertThat(DriverValidation.name(json("{\"name\":\"\\ufeff e\\u0301 合成姓名 \\u202f\"}"))).isEqualTo("é 合成姓名");
        assertThat(DriverValidation.name(json("{\"name\":\""+"😀".repeat(200)+"\"}"))).isEqualTo("😀".repeat(200));
        assertThat(DriverValidation.optionalText(json("{}"),"phone",50)).isNull();
        assertThat(DriverValidation.optionalText(json("{\"phone\":null}"),"phone",50)).isNull();
        assertThat(DriverValidation.text("\ufeff\u00a0 ","licenseReference",100)).isNull();
        assertThat(DriverValidation.text(" 聯絡方式待人工確認 ","email",254)).isEqualTo("聯絡方式待人工確認");
        for(String field:List.of("phone","email","licenseReference")){
            int bound=field.equals("phone")?50:field.equals("email")?254:100;
            assertThat(DriverValidation.text("😀".repeat(bound),field,bound)).isEqualTo("😀".repeat(bound));
            for(String invalid:List.of("x".repeat(bound+1),"x\u0000y","x\ry","\ud800"))assertThatThrownBy(()->DriverValidation.text(invalid,field,bound)).isInstanceOf(UpmsException.class);
        }
        for(String value:List.of("null","true","\"\"","\" \\ufeff\"","\""+"x".repeat(201)+"\""))assertThatThrownBy(()->DriverValidation.name(json("{\"name\":"+value+"}"))).isInstanceOf(UpmsException.class);
    }

    @Test void employmentAndDutyAreSeparateEnumsAndDoNotIncludeResourceOccupancy() {
        assertThat(EmploymentStatus.values()).containsExactlyInAnyOrder(EmploymentStatus.ACTIVE,EmploymentStatus.INACTIVE,EmploymentStatus.LEFT);
        assertThat(DutyStatus.values()).containsExactlyInAnyOrder(DutyStatus.ON_DUTY,DutyStatus.OFF_DUTY);
        for(String value:List.of("ACTIVE","INACTIVE","LEFT"))assertThat(DriverValidation.employmentStatus(value).name()).isEqualTo(value);
        for(String value:List.of("ON_DUTY","OFF_DUTY"))assertThat(DriverValidation.dutyStatus(value).name()).isEqualTo(value);
        for(String value:List.of("IN_USE","active","ACTIVE\n",""))assertThatThrownBy(()->DriverValidation.employmentStatus(value)).isInstanceOf(UpmsException.class);
        for(String value:List.of("AVAILABLE","ON_DUTY\n","","on_duty"))assertThatThrownBy(()->DriverValidation.dutyStatus(value)).isInstanceOf(UpmsException.class);
    }

    @Test void optionalSourcesAreStrictUuidAndLicenseDatesUseUnqualifiedGregorianNaturalDays() {
        String id=UUID.randomUUID().toString();assertThat(DriverValidation.optionalUuid(json("{\"accountId\":\""+id+"\"}"),"accountId")).isEqualTo(UUID.fromString(id));
        assertThat(DriverValidation.optionalUuid(json("{}"),"licenseType")).isNull();assertThat(DriverValidation.optionalUuid(json("{\"licenseType\":null}"),"licenseType")).isNull();
        for(String value:List.of("1-1-1-1-1",id+"\n",""))assertThatThrownBy(()->DriverValidation.uuid(value,"accountId")).isInstanceOf(UpmsException.class);
        for(String date:List.of("0001-01-01","1500-01-02","1582-10-10","2000-02-29","9999-12-31"))assertThat(DriverValidation.date(json("{\"licenseDueDate\":\""+date+"\"}"),"licenseDueDate")).isEqualTo(LocalDate.parse(date));
        assertThat(DriverValidation.date(json("{}"),"licenseDueDate")).isNull();
        for(String date:List.of("0000-01-01","10000-01-01","1900-02-29","2026-02-30","2026-1-01","2026-01-01\n",""))assertThatThrownBy(()->DriverValidation.date(json("{\"licenseDueDate\":\""+date.replace("\n","\\n")+"\"}"),"licenseDueDate")).isInstanceOf(UpmsException.class);
    }

    @Test void versionsAreMathematicalIntegersWithNoDoubleRoundingOrOverflow() {
        for(String value:List.of("0","-0.0","1.0","1e0","9007199254740991"))assertThat(DriverValidation.version(json("{\"version\":"+value+"}"))).isEqualTo(new java.math.BigDecimal(value).longValueExact());
        for(String value:List.of("-1","0.1","9007199254740992","9007199254740991.0000000001","1e10000000","1e-10000000","null","\"0\""))assertThatThrownBy(()->DriverValidation.version(json("{\"version\":"+value+"}"))).isInstanceOf(UpmsException.class);
    }

    @Test void structureIsStrictAndRejectsImmutableDerivedAuthOrOccupancyInputs() {
        DriverValidation.fields(json("{\"driverCode\":\"SYNTHETIC\",\"name\":\"合成司機\"}"),false);
        DriverValidation.fields(json("{\"version\":0,\"accountId\":null}"),true);
        for(String field:List.of("id","version","createdAt","createdBy","updatedBy","accountUsername","accountBindingValid","licenseTypeLabel","driverId","role","password","assignmentId"))assertThatThrownBy(()->DriverValidation.fields(json("{\"driverCode\":\"SYNTHETIC\",\"name\":\"合成司機\",\""+field+"\":null}"),false)).isInstanceOf(UpmsException.class);
        assertThatThrownBy(()->DriverValidation.fields(json("{\"version\":0,\"driverCode\":\"CHANGED\"}"),true)).isInstanceOf(UpmsException.class);
        for(String body:List.of("[]","{}","{\"version\":0}","{\"version\":null,\"name\":\"x\"}"))assertThatThrownBy(()->DriverValidation.fields(json(body),true)).isInstanceOf(UpmsException.class);
        for(String body:List.of("{","{} {}","{\"name\":\"A\",\"name\":\"B\"}"))assertThatThrownBy(()->json(body)).isInstanceOf(UpmsException.class);
        assertThatThrownBy(()->DriverValidation.parse(new byte[]{(byte)0xc3,0x28})).isInstanceOf(UpmsException.class);
    }

    @Test void pagedQueriesAreLiteralBoundedFixedOrderedAndResourceSpecific() {
        var query=new LinkedMultiValueMap<String,String>();query.add("page","100000");query.add("size","100");query.add("q","\ufeff e\u0301%_\\ ");
        var parsed=DriverPageQuery.parse(query,"drivers");assertThat(parsed.q()).isEqualTo("é%_\\");assertThat(parsed.pageable().getPageNumber()).isEqualTo(100000);
        assertThat(parsed.pageable().getSort().stream().map(value->value.getProperty()+":"+value.getDirection()).toList()).containsExactly("driverCode:ASC","id:ASC");
        var options=DriverPageQuery.parse(new LinkedMultiValueMap<>(),"options");assertThat(options.employmentStatus()).isEqualTo(EmploymentStatus.ACTIVE);assertThat(options.dutyStatus()).isEqualTo(DutyStatus.ON_DUTY);
        var accounts=DriverPageQuery.parse(new LinkedMultiValueMap<>(),"accounts");assertThat(accounts.pageable().getSort().stream().map(value->value.getProperty()+":"+value.getDirection()).toList()).containsExactly("username:ASC","id:ASC");
        for(String resource:List.of("drivers","options","accounts"))for(String key:List.of("sort","driverId","currentDriverId","unknown")){var bad=new LinkedMultiValueMap<String,String>();bad.add(key,"x");assertThatThrownBy(()->DriverPageQuery.parse(bad,resource)).isInstanceOf(UpmsException.class);}
        for(String key:List.of("employmentStatus","dutyStatus")){var bad=new LinkedMultiValueMap<String,String>();bad.add(key,key.equals("employmentStatus")?"ACTIVE":"ON_DUTY");assertThatThrownBy(()->DriverPageQuery.parse(bad,"options")).isInstanceOf(UpmsException.class);}
        var repeated=new LinkedMultiValueMap<String,String>();repeated.add("q","a");repeated.add("q","b");assertThatThrownBy(()->DriverPageQuery.parse(repeated,"drivers")).isInstanceOf(UpmsException.class);
    }

    private static JsonNode json(String raw){return DriverValidation.parse(raw.getBytes(StandardCharsets.UTF_8));}
}
