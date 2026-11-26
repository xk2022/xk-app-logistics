package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.*;
import com.xksystem.logistics.tms.completion.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompletionTest {
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static byte[] body(String version,String ids,String confirmation){return bytes("{\"expectedVersion\":"+version+",\"evidenceIds\":"+ids+",\"confirmSafeRelease\":"+confirmation+"}");}
    @Test void mathematicalVersionsAndCanonicalUuidSetsHaveOneHashIdentity(){
        UUID a=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),b=UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        var first=CompletionValidation.command(body("3","[\""+a+"\",\""+b+"\"]","true"));
        var other=CompletionValidation.command(body("3.0e0","[\""+b.toString().toUpperCase(java.util.Locale.ROOT)+"\",\""+a+"\"]","true"));
        assertThat(first.hash()).isEqualTo(other.hash());assertThat(first.evidenceIds()).containsExactly(a,b);
        assertThat(CompletionValidation.command(body("-0","[]","true")).expectedVersion()).isZero();
        assertThat(CompletionValidation.command(body("9007199254740991","[]","true")).expectedVersion()).isEqualTo(TmsValidation.MAX);
        assertThat(first.hash()).isNotEqualTo(CompletionValidation.command(body("4","[\""+a+"\",\""+b+"\"]","true")).hash());
    }
    @Test void strictRequestRejectsUnknownDuplicateInvalidUtf8AndSurrogateInput(){
        for(String value:List.of("{}","[]","null","{} {}","{\"expectedVersion\":3,\"expectedVersion\":3,\"evidenceIds\":[],\"confirmSafeRelease\":true}","{\"expectedVersion\":3,\"evidenceIds\":[],\"confirmSafeRelease\":true,\"reason\":\"x\"}","{\"expectedVersion\":3,\"evidenceIds\":[\"\\ud800\"],\"confirmSafeRelease\":true}"))
            assertThatThrownBy(()->CompletionValidation.command(bytes(value))).isInstanceOfSatisfying(CompletionFailure.class,error->assertThat(error.code()).isEqualTo("E0400"));
        assertThatThrownBy(()->CompletionValidation.command(new byte[]{(byte)0xc3,0x28})).isInstanceOf(CompletionFailure.class);
    }
    @Test void explicitTrueIsMandatoryButMissingEvidenceReachesThePostLedgerServiceGate(){
        for(String value:List.of("false","null","1","\"true\"","{}"))assertThatThrownBy(()->CompletionValidation.command(body("3","[]",value))).isInstanceOfSatisfying(CompletionFailure.class,error->assertThat(error.code()).isEqualTo("E0400"));
        assertThat(CompletionValidation.command(body("3","[]","true")).evidenceIds()).isEmpty();
        UUID id=UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThatThrownBy(()->CompletionValidation.command(body("3","[\""+id+"\",\""+id.toString().toUpperCase(java.util.Locale.ROOT)+"\"]","true"))).isInstanceOf(CompletionFailure.class);
        String many="["+String.join(",",java.util.stream.IntStream.range(0,33).mapToObj(i->"\""+UUID.randomUUID()+"\"").toList())+"]";
        assertThatThrownBy(()->CompletionValidation.command(body("3",many,"true"))).isInstanceOf(CompletionFailure.class);
    }
    @Test void unsafeOrNonMathematicalIntegerVersionsCannotBecomeTerminalCommands(){
        for(String value:List.of("-1","0.1","9007199254740992","1e10000000","1e-10000000","null","\"3\"","true"))assertThatThrownBy(()->CompletionValidation.command(body(value,"[]","true"))).isInstanceOf(CompletionFailure.class);
    }
    @Test void actualJsonStreamStopsAtOneOverflowByteWithoutTrustingDeclaredLength()throws Exception{
        var input=new ByteArrayInputStream(new byte[CompletionValidation.JSON_CAP+512]);
        assertThatThrownBy(()->CompletionValidation.bounded(input,-1)).isInstanceOfSatisfying(CompletionFailure.class,error->{assertThat(error.status()).isEqualTo(413);assertThat(error.code()).isEqualTo("E0413");assertThat(error.data()).isNull();});
        assertThat(input.available()).isEqualTo(511);
        assertThat(CompletionValidation.bounded(new ByteArrayInputStream(new byte[CompletionValidation.JSON_CAP]),-1)).hasSize(CompletionValidation.JSON_CAP);
        var untouched=new ByteArrayInputStream(new byte[9]);assertThatThrownBy(()->CompletionValidation.bounded(untouched,Long.MAX_VALUE)).isInstanceOf(CompletionFailure.class);assertThat(untouched.available()).isEqualTo(9);
    }
    @Test void errorsKeepTheIndependentExecutionVersionAndSettlementNamespace(){
        assertThat(CompletionFailure.version(4).data()).isEqualTo(Map.of("reason","VERSION_CONFLICT","actualVersion",4L));
        String[] reasons={"INVALID_STATE","POLICY_NOT_CONFIRMED","IDEMPOTENCY_KEY_REUSED","IDEMPOTENCY_IN_PROGRESS","PROTECTED_RELATION","EVIDENCE_REQUIRED","EVIDENCE_INVALID","UNRESOLVED_EXCEPTIONS"};
        for(int index=0;index<reasons.length;index++){var error=CompletionFailure.conflict(reasons[index],"合成");assertThat(error.status()).isEqualTo(409);assertThat(error.code()).isEqualTo("E200"+(index+2));assertThat(error.data()).isEqualTo(Map.of("reason",reasons[index]));}
        assertThatThrownBy(()->CompletionFailure.conflict("UNKNOWN","合成")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pathUuidAndExplicitIdempotencyKeyDoNotAcceptAmbiguousAliases(){
        assertThatThrownBy(()->CompletionValidation.uuid("1-1-1-1-1","assignmentId")).isInstanceOf(CompletionFailure.class);
        for(String value:List.of("","a b","x".repeat(129),"abc\n"))assertThatThrownBy(()->CompletionValidation.key(value)).isInstanceOf(CompletionFailure.class);
        assertThat(CompletionValidation.key("Synthetic-key:1_A.B")).isEqualTo("Synthetic-key:1_A.B");
    }
}
