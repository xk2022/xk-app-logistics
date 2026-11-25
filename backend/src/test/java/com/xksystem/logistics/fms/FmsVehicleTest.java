package com.xksystem.logistics.fms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.upms.UpmsException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

/** Pure contract boundaries; database, authorization and concurrency are exercised by FmsVehicleIT. */
class FmsVehicleTest {
    @Test
    void canonicalPlateUsesUnicodeEdgesNfcRootUppercaseAndPostExpansionCodepointBounds() {
        assertThat(VehicleValidation.plateNo("\u001c\ufeff\u00a0 e\u0301-ab  c \u202f\ufeff\u001f")).isEqualTo("É-AB  C");
        assertThat(VehicleValidation.plateNo("ß".repeat(20))).isEqualTo("SS".repeat(20));
        assertThat(VehicleValidation.plateNo(" ".repeat(200)+"a".repeat(40)+"\ufeff".repeat(200))).isEqualTo("A".repeat(40));
        assertThat(VehicleValidation.plateNo("😀".repeat(40))).isEqualTo("😀".repeat(40));
        for(String invalid:List.of(""," \u00a0\ufeff","ß".repeat(20)+"a","😀".repeat(41),"AB\u0000C","AB\u001cC","AB\nC","\ud800","\udc00"))
            assertThatThrownBy(()->VehicleValidation.plateNo(invalid)).isInstanceOf(UpmsException.class);
    }

    @Test
    void optionalRegistrationTextNormalizesWithoutInventingNationalFormattingRules() {
        assertThat(VehicleValidation.text("\ufeff e\u0301-非制式車籍 \u202f","registrationNo",100)).isEqualTo("é-非制式車籍");
        assertThat(VehicleValidation.text("\ufeff\u00a0 ","registrationNo",100)).isNull();
        assertThat(VehicleValidation.text("😀".repeat(100),"registrationNo",100)).isEqualTo("😀".repeat(100));
        for(String invalid:List.of("x".repeat(101),"x\u0000y","x\ry","\ud800"))assertThatThrownBy(()->VehicleValidation.text(invalid,"registrationNo",100)).isInstanceOf(UpmsException.class);
    }

    @Test
    void capacityIsAnExactNullableNonnegativeDecimalStringWithItsOriginalScale() {
        String largestWire="12345678901234567890123456789.12",tiny="0."+"0".repeat(29)+"1";
        for(String value:List.of("0","0.000",largestWire,tiny))assertThat(VehicleValidation.capacityTon(json("{\"capacityTon\":\""+value+"\"}")).toPlainString()).isEqualTo(value);
        assertThat(VehicleValidation.capacityTon(json("{}"))).isNull();assertThat(VehicleValidation.capacityTon(json("{\"capacityTon\":null}"))).isNull();
        for(String raw:List.of("0","true","\"\"","\"-0\"","\"01\"","\"1e2\"","\"1.\"","\" 1\"","\"1\\n\"","\""+"1".repeat(33)+"\""))
            assertThatThrownBy(()->VehicleValidation.capacityTon(json("{\"capacityTon\":"+raw+"}"))).isInstanceOf(UpmsException.class);
    }

    @Test
    void datesUseGregorianYearsOneThrough9999WithoutRelativeOrLegalInspectionPolicies() {
        for(String date:List.of("0001-01-01","1500-01-01","2000-02-29","9999-12-31"))assertThat(VehicleValidation.date(json("{\"inspectionDueDate\":\""+date+"\"}"),"inspectionDueDate")).isEqualTo(LocalDate.parse(date));
        assertThat(VehicleValidation.date(json("{\"registrationDate\":null}"),"registrationDate")).isNull();
        for(String raw:List.of("0000-01-01","10000-01-01","1900-02-29","2026-02-30","2026-1-01","2026-01-01\n","2026-01-01T00:00:00Z",""))
            assertThatThrownBy(()->VehicleValidation.date(json("{\"registrationDate\":\""+raw.replace("\n","\\n")+"\"}"),"registrationDate")).isInstanceOf(UpmsException.class);
    }

    @Test
    void versionsAcceptMathematicalJsonIntegersWithoutRoundingOrUnsafeOverflow() {
        for(String value:List.of("0","-0.0","0e0","1.0","1e0","9007199254740991"))assertThat(VehicleValidation.version(json("{\"version\":"+value+"}"))).isEqualTo(new BigDecimal(value).longValueExact());
        for(String value:List.of("-1","0.1","9007199254740992","9007199254740991.0000000001","1e10000000","1e-10000000","\"0\"","null"))
            assertThatThrownBy(()->VehicleValidation.version(json("{\"version\":"+value+"}"))).isInstanceOf(UpmsException.class);
    }

    @Test
    void structuralValidationRejectsUnknownSystemOccupancyAndDuplicateFieldsAndMalformedUtf8() {
        String type=UUID.randomUUID().toString();
        VehicleValidation.fields(json("{\"plateNo\":\"SYNTHETIC\",\"vehicleType\":\""+type+"\"}"),false);
        VehicleValidation.fields(json("{\"version\":0,\"registrationNo\":null}"),true);
        for(String field:List.of("id","version","createdBy","updatedAt","vehicleTypeLabel","vehicleTypeEnabled","driverId","reservationId","inUse"))
            assertThatThrownBy(()->VehicleValidation.fields(json("{\"plateNo\":\"SYNTHETIC\",\"vehicleType\":\""+type+"\",\""+field+"\":null}"),false)).isInstanceOf(UpmsException.class);
        for(String body:List.of("[]","{}","{\"version\":0}","{\"version\":null,\"plateNo\":\"X\"}"))assertThatThrownBy(()->VehicleValidation.fields(json(body),true)).isInstanceOf(UpmsException.class);
        for(String body:List.of("{} {}","{","{\"plateNo\":\"A\",\"plateNo\":\"B\"}"))assertThatThrownBy(()->json(body)).isInstanceOf(UpmsException.class);
        assertThatThrownBy(()->VehicleValidation.parse(new byte[]{(byte)0xc3,0x28})).isInstanceOf(UpmsException.class);
    }

    @Test
    void uuidAndAdministrativeStatesDoNotBroadenIntoCountryFormatsOrDispatchState() {
        String id="12345678-abcd-0000-ffff-1234567890ab";
        assertThat(VehicleValidation.uuid(id.toUpperCase(java.util.Locale.ROOT),"id")).isEqualTo(UUID.fromString(id));
        for(String invalid:List.of("1-1-1-1-1",id+"\n","",id+"x"))assertThatThrownBy(()->VehicleValidation.uuid(invalid,"id")).isInstanceOf(UpmsException.class);
        assertThat(AdministrativeStatus.values()).containsExactlyInAnyOrder(AdministrativeStatus.AVAILABLE,AdministrativeStatus.MAINTENANCE,AdministrativeStatus.INACTIVE);
        for(String invalid:List.of("IN_USE","available","","AVAILABLE\n"))assertThatThrownBy(()->VehicleValidation.status(invalid)).isInstanceOf(UpmsException.class);
    }

    @Test
    void pagesHaveExplicitBoundsFixedStableOrderAndOptionsOnlyAdministrativeAvailable() {
        var query=new LinkedMultiValueMap<String,String>();query.add("page","100000");query.add("size","100");query.add("q"," \ufeffe\u0301%_\\ ");
        var parsed=VehiclePageQuery.parse(query,false);assertThat(parsed.pageable().getPageNumber()).isEqualTo(100000);assertThat(parsed.pageable().getPageSize()).isEqualTo(100);
        assertThat(parsed.q()).isEqualTo("é%_\\");assertThat(parsed.pageable().getSort().stream().map(order->order.getProperty()+":"+order.getDirection()).toList()).containsExactly("plateNo:ASC","id:ASC");
        assertThat(VehiclePageQuery.parse(new LinkedMultiValueMap<>(),true).administrativeStatus()).isEqualTo(AdministrativeStatus.AVAILABLE);
        for(String field:List.of("sort","enabled","company","owner","unknown")){var invalid=new LinkedMultiValueMap<String,String>();invalid.add(field,"x");assertThatThrownBy(()->VehiclePageQuery.parse(invalid,false)).isInstanceOf(UpmsException.class);}
        var repeated=new LinkedMultiValueMap<String,String>();repeated.add("page","0");repeated.add("page","0");assertThatThrownBy(()->VehiclePageQuery.parse(repeated,false)).isInstanceOf(UpmsException.class);
        var bypass=new LinkedMultiValueMap<String,String>();bypass.add("administrativeStatus","AVAILABLE");assertThatThrownBy(()->VehiclePageQuery.parse(bypass,true)).isInstanceOf(UpmsException.class);
    }

    private static JsonNode json(String raw){return VehicleValidation.parse(raw.getBytes(StandardCharsets.UTF_8));}
}
