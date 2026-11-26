package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

class TmsValidationTest {
    private static final String DRIVER="00000000-0000-0000-0000-000000000001",MAIN="00000000-0000-0000-0000-000000000002";
    private static JsonNode json(String text){return TmsValidation.parse(text.getBytes(StandardCharsets.UTF_8));}
    private static String assign(String generation){return "{\"expectedGeneration\":"+generation+",\"driverId\":\""+DRIVER+"\",\"mainVehicleId\":\""+MAIN+"\",\"trailerId\":null}";}
    @Test void mathematicalIntegersAreExactAndDoNotExpandExtremeExponents(){
        for(String value:List.of("0","-0.0","0e0","1","1.0","1e0","9007199254740991"))assertThat(TmsValidation.command(json(assign(value)),TmsValidation.Action.ASSIGN).expectedGeneration()).isEqualTo(new java.math.BigDecimal(value).longValueExact());
        for(String value:List.of("null","\"0\"","true","-1","0.1","9007199254740992","9007199254740991.0000000001","1e10000000","1e-10000000"))assertThatThrownBy(()->TmsValidation.command(json(assign(value)),TmsValidation.Action.ASSIGN)).isInstanceOf(TmsFailure.class);
    }
    @Test void strictShapesRejectOmissionExtraFieldsFalseNullableReferencesAndDuplicateKeys(){
        assertThat(TmsValidation.command(json(assign("0")),TmsValidation.Action.ASSIGN).trailerId()).isNull();
        for(String raw:List.of("[]","{}",assign("0").replace(",\"trailerId\":null",""),assign("0").replace("\"trailerId\":null","\"trailerId\":false"),assign("0").replace("}",",\"actorId\":null}")))assertThatThrownBy(()->TmsValidation.command(json(raw),TmsValidation.Action.ASSIGN)).isInstanceOf(TmsFailure.class);
        for(String raw:List.of("{} {}","{","{\"expectedGeneration\":0,\"expectedGeneration\":1}"))assertThatThrownBy(()->json(raw)).isInstanceOf(TmsFailure.class);
        assertThatThrownBy(()->TmsValidation.parse(new byte[]{(byte)0xc3,0x28})).isInstanceOf(TmsFailure.class);
        assertThatThrownBy(()->json("{\"reason\":\"\\ud800\"}")).isInstanceOf(TmsFailure.class);
    }
    @Test void canonicalHashNormalizesOnlyJsonIntegersAndPropertyOrder(){
        String first=TmsValidation.hash(json(assign("1")));
        assertThat(TmsValidation.hash(json(assign("1.0e0")))).isEqualTo(first);
        assertThat(TmsValidation.hash(json("{\"trailerId\":null,\"mainVehicleId\":\""+MAIN+"\",\"driverId\":\""+DRIVER+"\",\"expectedGeneration\":1}"))).isEqualTo(first);
        assertThat(TmsValidation.hash(json("{\"expectedGeneration\":1,\"reason\":\"e\\u0301\"}"))).isNotEqualTo(TmsValidation.hash(json("{\"expectedGeneration\":1,\"reason\":\"é\"}")));
        assertThat(TmsValidation.hash(json("{\"x\":null}"))).isNotEqualTo(TmsValidation.hash(json("{}")));
        assertThat(TmsValidation.hash(json("{\"x\":[\"a\",\"b\"]}"))).isNotEqualTo(TmsValidation.hash(json("{\"x\":[\"b\",\"a\"]}")));
    }
    @Test void reasonsNormalizeNfcAndUnicodeEdgesButPreservePermittedLineControls(){
        var command=TmsValidation.command(json("{\"expectedGeneration\":0,\"reason\":\"\\ufeff e\\u0301\\r\\n\\t合成原因 \\u00a0\"}"),TmsValidation.Action.REVOKE);
        assertThat(command.reason()).isEqualTo("é\r\n\t合成原因");
        assertThat(TmsValidation.text("x".repeat(500),"reason",500,true)).hasSize(500);
        for(String reason:List.of(""," \\ufeff ","x\\u0000y","x\\u001cy","x".repeat(501)))assertThatThrownBy(()->TmsValidation.command(json("{\"expectedGeneration\":0,\"reason\":\""+reason+"\"}"),TmsValidation.Action.REVOKE)).isInstanceOf(TmsFailure.class);
    }
    @Test void uuidKeysAndVehiclePositionsAreNotSilentlyRepaired(){
        for(String uuid:List.of("1-1-1-1-1",DRIVER+"\n","",DRIVER+"x"))assertThatThrownBy(()->TmsValidation.uuid(uuid,"id")).isInstanceOf(TmsFailure.class);
        for(String key:List.of("","x\n","a,b","x".repeat(129)))assertThatThrownBy(()->TmsValidation.key(key)).isInstanceOf(TmsFailure.class);
        assertThat(TmsValidation.key("SYNTHETIC_key.1:-")).isEqualTo("SYNTHETIC_key.1:-");
        assertThatThrownBy(()->TmsValidation.command(json(assign("0").replace("\"trailerId\":null","\"trailerId\":\""+MAIN+"\"")),TmsValidation.Action.ASSIGN)).isInstanceOf(TmsFailure.class);
    }
    @Test void boundedQueriesKeepServerTotalsAndRejectUnknownOrRepeatedFilters(){
        var query=new LinkedMultiValueMap<String,String>();query.add("page","100000");query.add("size","100");query.add("state","ALL");query.add("q"," \ufeffe\u0301%_\\ ");
        var parsed=TmsQuery.parse(query,"tasks");assertThat(parsed.page()).isEqualTo(100000);assertThat(parsed.size()).isEqualTo(100);assertThat(parsed.q()).isEqualTo("é%_\\");
        assertThat(TmsQuery.parse(new LinkedMultiValueMap<>(),"tasks").state()).isEqualTo("UNASSIGNED");
        for(String field:List.of("driverId","accountId","orderId","q","state")){var invalid=new LinkedMultiValueMap<String,String>();invalid.add(field,"x");assertThatThrownBy(()->TmsQuery.parse(invalid,"self")).isInstanceOf(TmsFailure.class);}
        var duplicate=new LinkedMultiValueMap<String,String>();duplicate.add("page","0");duplicate.add("page","0");assertThatThrownBy(()->TmsQuery.parse(duplicate,"tasks")).isInstanceOf(TmsFailure.class);
        for(String invalid:List.of("-1","100001","1.0","2147483648")){var bad=new LinkedMultiValueMap<String,String>();bad.add("page",invalid);assertThatThrownBy(()->TmsQuery.parse(bad,"tasks")).isInstanceOf(TmsFailure.class);}
    }
    @Test void resourceContextIsExplicitAndNoSelfOrHistoryIdentifierIsAccepted(){
        var query=new LinkedMultiValueMap<String,String>();query.add("taskId",DRIVER);query.add("position","TRAILER");assertThat(TmsQuery.parse(query,"resources").position()).isEqualTo(ResourcePosition.TRAILER);
        for(String position:List.of("MAIN_VEHICLE","vehicle","MAIN\n","")){query.set("position",position);assertThatThrownBy(()->TmsQuery.parse(query,"resources")).isInstanceOf(TmsFailure.class);}
        assertThatThrownBy(()->TmsQuery.parse(new LinkedMultiValueMap<>(),"resources")).isInstanceOf(TmsFailure.class);
        var history=new LinkedMultiValueMap<String,String>();history.add("page","0");assertThatThrownBy(()->TmsQuery.parse(history,"history")).isInstanceOf(TmsFailure.class);
    }
}
