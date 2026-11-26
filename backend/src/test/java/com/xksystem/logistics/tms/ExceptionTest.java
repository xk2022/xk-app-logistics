package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.*;
import com.xksystem.logistics.tms.exceptions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.json.JsonMapper;

class ExceptionTest {
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String encoded(String value){return JsonMapper.builder().build().writeValueAsString(value);}
    @Test void rawFactsKeepNulNfdWhitespaceNewlinesAndDifferentHashIdentity() {
        String value=" \u0000 e\u0301\r\n 合成異常 \ufeff ";
        var input=ExceptionValidation.report(bytes("{\"expectedExecutionVersion\":1,\"description\":"+encoded(value)+"}"));
        assertThat(input.text()).isEqualTo(value);
        assertThat(input.hash()).isNotEqualTo(ExceptionValidation.report(bytes("{\"expectedExecutionVersion\":1,\"description\":"+encoded(value.strip())+"}")).hash());
        assertThat(input.hash()).isNotEqualTo(ExceptionValidation.report(bytes("{\"expectedExecutionVersion\":1,\"description\":"+encoded(value.replace("e\u0301","é"))+"}")).hash());
        assertThat(ExceptionValidation.rawText("\u0085","description",2000)).isEqualTo("\u0085");
        for(String blank:List.of(""," \t\n\u001c\u00a0\ufeff"))assertThatThrownBy(()->ExceptionValidation.rawText(blank,"description",2000)).isInstanceOf(ExceptionFailure.class);
        assertThatThrownBy(()->ExceptionValidation.rawText("x".repeat(2001),"description",2000)).isInstanceOf(ExceptionFailure.class);
    }
    @Test void strictJsonAndUnicodeErrorsAreCommonValidationNeverPostLedgerSettlementCodes() {
        for(String value:List.of("{}","[]","null","{} {}","{\"expectedExecutionVersion\":1,\"expectedExecutionVersion\":1,\"description\":\"x\"}","{\"expectedExecutionVersion\":1,\"description\":\"\\ud800\"}","{\"expectedExecutionVersion\":1,\"description\":\"x\",\"blocking\":false}"))
            assertThatThrownBy(()->ExceptionValidation.report(bytes(value))).isInstanceOfSatisfying(ExceptionFailure.class,failure->assertThat(failure.code()).isEqualTo("E0400"));
        assertThatThrownBy(()->ExceptionValidation.report(new byte[]{(byte)0xc3,0x28})).isInstanceOf(ExceptionFailure.class);
    }
    @Test void safeMathematicalVersionsAndCanonicalEvidenceSetsAreStable() {
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();String body="{\"expectedVersion\":1,\"note\":\" 原始 \u0000 \\n\",\"evidenceIds\":[\""+first+"\",\""+second+"\"]}";
        // JSON escapes, not a literal unescaped control byte, represent a raw Unicode fact.
        body=body.replace("\u0000","\\u0000");String alternate=body.replace("\"expectedVersion\":1,","\"expectedVersion\":1.0e0,").replace(first+"\",\""+second,second.toString().toUpperCase(java.util.Locale.ROOT)+"\",\""+first);
        assertThat(ExceptionValidation.submit(bytes(body)).hash()).isEqualTo(ExceptionValidation.submit(bytes(alternate)).hash());
        String duplicate="{\"expectedVersion\":1,\"note\":\"合成\",\"evidenceIds\":[\""+first+"\",\""+first.toString().toUpperCase(java.util.Locale.ROOT)+"\"]}";
        assertThatThrownBy(()->ExceptionValidation.submit(bytes(duplicate))).isInstanceOf(ExceptionFailure.class);
        for(String number:List.of("-1","0.1","9007199254740992","1e10000000","1e-10000000","null","\"1\""))assertThatThrownBy(()->ExceptionValidation.management(bytes("{\"expectedVersion\":"+number+",\"reason\":\"合成\"}"))).isInstanceOf(ExceptionFailure.class);
        assertThat(ExceptionValidation.management(bytes("{\"expectedVersion\":9007199254740991,\"reason\":\"合成\"}")).version()).isEqualTo(TmsValidation.MAX);
    }
    @Test void twoVersionUnitsNeverShareAnAmbiguousActualVersionField() {
        assertThat(ExceptionFailure.version(7,false).data()).isEqualTo(Map.of("reason","VERSION_CONFLICT","actualVersion",7L));
        assertThat(ExceptionFailure.version(3,true).data()).isEqualTo(Map.of("reason","VERSION_CONFLICT","actualExecutionVersion",3L));
        for(String value:List.of("01","+1","1e0","1.0"," 1","1 ","1,1","9007199254740992"))assertThatThrownBy(()->ExceptionValidation.headerVersion(value,"X-Exception-Version")).isInstanceOfSatisfying(ExceptionFailure.class,failure->assertThat(failure.code()).isEqualTo("E0400"));
        assertThat(ExceptionValidation.headerVersion("0","X-Execution-Version")).isZero();
    }
    @Test void jsonAndRawPhotoCapsReadOnlyTheSingleOverflowByte()throws Exception {
        var input=new ByteArrayInputStream(new byte[ExceptionValidation.JSON_CAP+1024]);
        assertThatThrownBy(()->ExceptionValidation.bounded(input,-1,ExceptionValidation.JSON_CAP)).isInstanceOfSatisfying(ExceptionFailure.class,failure->{assertThat(failure.status()).isEqualTo(413);assertThat(failure.data()).isNull();});
        assertThat(input.available()).isEqualTo(1023);
        assertThat(ExceptionValidation.bounded(new ByteArrayInputStream(new byte[ExceptionValidation.JSON_CAP]),-1,ExceptionValidation.JSON_CAP)).hasSize(ExceptionValidation.JSON_CAP);
        assertThatThrownBy(()->ExceptionValidation.bounded(new ByteArrayInputStream(new byte[0]),Long.MAX_VALUE,ExceptionValidation.PHOTO_CAP)).isInstanceOf(ExceptionFailure.class);
    }
    @Test void strictQueryScopesDoNotBroadenDriverOrManagerData() {
        var manager=new LinkedMultiValueMap<String,String>();manager.add("assignmentId",UUID.randomUUID().toString());manager.add("status","OPEN");
        assertThat(ExceptionValidation.query(manager,"manager").status()).isEqualTo(ExceptionStatus.OPEN);
        assertThatThrownBy(()->ExceptionValidation.query(manager,"self")).isInstanceOf(ExceptionFailure.class);
        for(String field:List.of("driverId","accountId","company","q","sort")){var unknown=new LinkedMultiValueMap<String,String>();unknown.add(field,"x");assertThatThrownBy(()->ExceptionValidation.query(unknown,"manager")).isInstanceOf(ExceptionFailure.class);}
        var duplicate=new LinkedMultiValueMap<String,String>();duplicate.add("size","20");duplicate.add("size","20");assertThatThrownBy(()->ExceptionValidation.query(duplicate,"page")).isInstanceOf(ExceptionFailure.class);
    }
    @Test void normalPolicyIsUnconfirmedAndReportedPhasesDoNotIncludeWaitingOrCompleted() {
        var policy=new UnavailableExceptionConfiguration().exceptionPolicy();assertThat(policy.mode()).isEqualTo(PolicyMode.UNCONFIRMED);
        assertThat(policy.allows(ExceptionPolicy.Action.RESOLVE,null)).isFalse();assertThat(policy.requirements(null)).isEmpty();assertThat(policy.managerCanRead(null,null)).isFalse();
        assertThat(ReportedPhase.values()).containsExactly(ReportedPhase.DEPARTED,ReportedPhase.IN_PROGRESS,ReportedPhase.AWAITING_COMPLETION);
    }
}
