package com.xksystem.logistics.tms;

import static org.assertj.core.api.Assertions.*;

import com.xksystem.logistics.tms.execution.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import com.xksystem.logistics.upms.security.UpmsSessions;
import tools.jackson.databind.json.JsonMapper;

class ExecutionTest {
    private static ExecutionValidation.Command command(String value) {
        return ExecutionValidation.command(value.getBytes(StandardCharsets.UTF_8));
    }
    @Test void executionVersionsAreMathematicalSafeIntegersWithoutExponentExpansion() {
        String zero=command("{\"expectedVersion\":0}").hash();
        for(String number:List.of("0","-0.0","0e20")) {
            var parsed=command("{\"expectedVersion\":"+number+"}");
            assertThat(parsed.expectedVersion()).isZero();assertThat(parsed.hash()).isEqualTo(zero);
        }
        String one=command("{\"expectedVersion\":1}").hash();
        for(String number:List.of("1","1.0","1e0"))assertThat(command("{\"expectedVersion\":"+number+"}").hash()).isEqualTo(one);
        assertThat(command("{\"expectedVersion\":9007199254740991}").expectedVersion()).isEqualTo(9_007_199_254_740_991L);
        for(String number:List.of("-1","0.1","9007199254740992","9007199254740991.0000001","1e10000000","1e-10000000","null","true","\"0\""))
            assertThatThrownBy(()->command("{\"expectedVersion\":"+number+"}")).isInstanceOf(ExecutionFailure.class);
    }
    @Test void strictCommandGrammarRejectsDuplicatesIdentityOverridesMalformedUtf8AndSurrogates() {
        for(String raw:List.of("{}","[]","null","{","{} {}","{\"expectedVersion\":0,\"expectedVersion\":0}",
                "{\"expectedVersion\":0,\"driverId\":null}","{\"expectedVersion\":0,\"reason\":\"\\ud800\"}"))
            assertThatThrownBy(()->command(raw)).isInstanceOfSatisfying(ExecutionFailure.class,failure->{
                assertThat(failure.status()).isEqualTo(400);assertThat(failure.code()).isEqualTo("E0400");
            });
        assertThatThrownBy(()->ExecutionValidation.command(new byte[]{(byte)0xc3,0x28})).isInstanceOf(ExecutionFailure.class);
        assertThat(command(" { \"expectedVersion\" : 0.0 } ").hash()).isEqualTo(command("{\"expectedVersion\":0}").hash());
    }
    @Test void assignmentPathsAndIdempotencyKeysAreStrictAndCaseSensitive() {
        UUID id=UUID.randomUUID();
        assertThat(ExecutionValidation.assignmentId(id.toString().toUpperCase(java.util.Locale.ROOT))).isEqualTo(id);
        for(String bad:List.of("1-1-1-1-1",id+"\n",id+" ",""))
            assertThatThrownBy(()->ExecutionValidation.assignmentId(bad)).isInstanceOf(ExecutionFailure.class);
        assertThat(ExecutionValidation.key("Aa._:-09")).isEqualTo("Aa._:-09");
        for(String bad:List.of("","key,key","key\n","x".repeat(129)))
            assertThatThrownBy(()->ExecutionValidation.key(bad)).isInstanceOf(ExecutionFailure.class);
    }
    @Test void departureReceiptContainsOnlyItsThreeFieldsAndWaitingIsVirtual() {
        var json=JsonMapper.builder().build();
        var waiting=ExecutionDtos.View.waiting();
        assertThat(waiting.phase()).isEqualTo(ExecutionPhase.WAITING);assertThat(waiting.version()).isZero();assertThat(waiting.departedAt()).isNull();
        UUID assignment=UUID.randomUUID(),task=UUID.randomUUID();
        var result=new ExecutionDtos.DepartureResult(assignment,task,new ExecutionDtos.View(ExecutionPhase.DEPARTED,1,Instant.parse("2026-11-26T00:00:00.123456Z")));
        var body=json.valueToTree(result);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("assignmentId","taskId","execution");
        assertThat(body.get("execution").propertyNames()).containsExactlyInAnyOrder("phase","version","departedAt");
    }
    @Test void executionBusinessCodesRemainSeparateFromManagerGenerationErrors() {
        var codes=Map.of("VERSION_CONFLICT","E1701","INVALID_STATE","E1702","POLICY_NOT_CONFIRMED","E1703",
                "IDEMPOTENCY_KEY_REUSED","E1704","IDEMPOTENCY_IN_PROGRESS","E1705","PROTECTED_RELATION","E1706");
        codes.forEach((reason,code)->{
            var failure=ExecutionFailure.conflict(reason,"合成拒絕",reason.equals("VERSION_CONFLICT")?1L:null);
            assertThat(failure.status()).isEqualTo(409);assertThat(failure.code()).isEqualTo(code);
            assertThat(((Map<?,?>)failure.data()).get("reason")).isEqualTo(reason);
        });
        assertThat(ExecutionFailure.conflict("VERSION_CONFLICT","合成版本衝突",1L).data())
                .isEqualTo(Map.of("reason","VERSION_CONFLICT","actualVersion",1L));
        assertThat(ExecutionFailure.missing().data()).isNull();
    }
    @Test void nestedCommitTimeForeignKeyUsesExecutionCodeAndSanitizesDatabaseFailures() {
        var errors=new ExecutionErrors();
        var protectedReply=errors.unexpected(new RuntimeException("sensitive",new SQLException("private SQL","23503")));
        assertThat(protectedReply.getStatusCode().value()).isEqualTo(409);
        assertThat(protectedReply.getBody().code()).isEqualTo("E1706");
        assertThat(protectedReply.getBody().data()).isEqualTo(Map.of("reason","PROTECTED_RELATION"));
        var generic=errors.unexpected(new RuntimeException("private payload"));
        assertThat(generic.getStatusCode().value()).isEqualTo(500);assertThat(generic.getBody().code()).isEqualTo("E0500");
        assertThat(generic.getBody().message()).doesNotContain("private");assertThat(generic.getBody().data()).isNull();
    }
    @Test void currentSessionCheckAllowsConcurrentAccessRefreshButNeverRevivesLogoutOrAnotherIdentity() {
        Instant now=Instant.parse("2026-11-26T12:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        var requestPrincipal=new SessionPrincipal(UUID.randomUUID(),7,now.minusSeconds(60),now.minusSeconds(30));
        var session=new MockHttpSession();
        session.setAttribute(UpmsSessions.AUTH,new SessionPrincipal(requestPrincipal.id(),7,requestPrincipal.authenticatedAt(),now));
        assertThatCode(()->UpmsSessions.requireCurrent(session,requestPrincipal,clock)).doesNotThrowAnyException();
        session.setAttribute(UpmsSessions.AUTH,new SessionPrincipal(UUID.randomUUID(),7,requestPrincipal.authenticatedAt(),now));
        assertThatThrownBy(()->UpmsSessions.requireCurrent(session,requestPrincipal,clock)).isInstanceOf(UpmsException.class);
        session.setAttribute(UpmsSessions.AUTH,new SessionPrincipal(requestPrincipal.id(),7,requestPrincipal.authenticatedAt(),now.minusSeconds(1800)));
        assertThatThrownBy(()->UpmsSessions.requireCurrent(session,requestPrincipal,clock)).isInstanceOf(UpmsException.class);
        session.invalidate();
        assertThatThrownBy(()->UpmsSessions.requireCurrent(session,requestPrincipal,clock)).isInstanceOfSatisfying(UpmsException.class,failure->assertThat(failure.status()).isEqualTo(401));
    }
}
