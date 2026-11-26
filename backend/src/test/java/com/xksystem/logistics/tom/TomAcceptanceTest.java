package com.xksystem.logistics.tom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.support.TomContract;
import com.xksystem.logistics.tom.domain.AcceptanceChecker;
import com.xksystem.logistics.tom.domain.AcceptanceRequest;
import com.xksystem.logistics.tom.domain.DraftCodec;
import com.xksystem.logistics.tom.domain.TomDomainException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class TomAcceptanceTest {
    @Test void acceptanceRequestContainsOnlyRequiredExpectedVersionAndDoesNotMutateOriginalJson() {
        JsonNode input=DraftCodec.parseJson("{\"expectedVersion\":12}");JsonNode before=input.deepCopy();
        assertThat(AcceptanceRequest.parse(input).expectedVersion()).isEqualTo(12);assertThat(input).isEqualTo(before);
        for(String raw:List.of("{}","[]","null","{\"expectedVersion\":null}","{\"expectedVersion\":0,\"actorId\":\"synthetic\"}","{\"expectedVersion\":0,\"acceptedAt\":\"2026-01-01T00:00:00Z\"}","{\"expectedVersion\":0,\"draft\":{}}"))assertThatThrownBy(()->AcceptanceRequest.parse(DraftCodec.parseJson(raw))).isInstanceOfSatisfying(TomDomainException.class,error->{assertThat(error.status()).isEqualTo(400);assertThat(error.code()).isEqualTo("VALIDATION_ERROR");assertThat(error.fieldErrors()).isNotEmpty();});
    }
    @Test void mathematicalIntegerRepresentationsHaveExactValuesAndIdenticalCanonicalRequestHashes() {
        for(String raw:List.of("0","-0.0","0e0","1","1.0","1e0","9007199254740991","9007199254740991.0"))assertThat(AcceptanceRequest.parse(DraftCodec.parseJson("{\"expectedVersion\":"+raw+"}")).expectedVersion()).isEqualTo(new BigDecimal(raw).longValueExact());
        String one=DraftCodec.canonicalHash(DraftCodec.parseJson("{\"expectedVersion\":1}"));
        for(String raw:List.of("1.0","1e0","1.0000"))assertThat(DraftCodec.canonicalHash(DraftCodec.parseJson("{ \"expectedVersion\" : "+raw+" }"))).isEqualTo(one);
        assertThat(DraftCodec.canonicalHash(DraftCodec.parseJson("{\"expectedVersion\":2}"))).isNotEqualTo(one);
    }
    @Test void invalidVersionTypesFractionsAndHugeExponentsCannotRoundOrAllocateUnboundedIntegers() {
        for(String value:List.of("-1","0.1","9007199254740992","9007199254740991.00000001","1e10000000","1e-10000000","\"1\"","true","[]","{}"))assertThatThrownBy(()->AcceptanceRequest.parse(DraftCodec.parseJson("{\"expectedVersion\":"+value+"}"))).isInstanceOfSatisfying(TomDomainException.class,error->{assertThat(error.status()).isEqualTo(400);assertThat(error.fieldErrors()).anySatisfy(issue->{assertThat(issue.path()).isEqualTo("/expectedVersion");assertThat(issue.fieldId()).isEqualTo("AF01");});});
    }
    @Test void strictOriginalJsonParserRejectsDuplicateTrailingAndBrokenRequestJson() {
        for(String raw:List.of("{\"expectedVersion\":0,\"expectedVersion\":1}","{\"expectedVersion\":0} {}","{","{\"expectedVersion\":NaN}","{\"expectedVersion\":Infinity}","{\"expectedVersion\":\"\\ud800\"}"))assertThatThrownBy(()->AcceptanceRequest.parse(DraftCodec.parseJson(raw))).isInstanceOf(TomDomainException.class);
    }
    @Test void originalCompletenessRulesStayPureAndNotGrantedEvenWhenTheOrderIsAlreadyAccepted() {
        ObjectNode order=(ObjectNode)TomContract.example("schemaPositive","DEMO-LOCAL-CONTRACT-response").get("value").get("data").deepCopy();order.put("acceptanceStatus","ACCEPTED");JsonNode before=order.deepCopy();
        JsonNode result=AcceptanceChecker.check(order,true);assertThat(result.get("confirmedRulesOutcome").asString()).isEqualTo("COMPLETE");assertThat(result.get("operationalAcceptance").asString()).isEqualTo("NOT_GRANTED");assertThat(result.get("pendingPolicyIds").size()).isPositive();assertThat(result.get("missingFields").size()).isZero();assertThat(order).isEqualTo(before);
    }
}
