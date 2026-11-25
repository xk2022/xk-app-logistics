package com.xksystem.logistics.tom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.support.TomContract;
import com.xksystem.logistics.tom.domain.AcceptanceChecker;
import com.xksystem.logistics.tom.domain.DraftCodec;
import com.xksystem.logistics.tom.domain.TomDomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class TomDomainTest {
    static Stream<Arguments> originalSchemaExamples() {
        return Stream.concat(TomContract.group("schemaPositive").stream().map(value -> Arguments.of(value.get("id").asString(), value, true)),
                TomContract.group("schemaNegative").stream().map(value -> Arguments.of(value.get("id").asString(), value, false)));
    }

    @ParameterizedTest(name = "original schema fixture {0}")
    @MethodSource("originalSchemaExamples")
    void sourceContractAssertionsMatchAllFortyOneOriginalGoldenSchemaCases(String id, JsonNode example, boolean valid) {
        assertThat(TomContract.errors(example.get("schema").asString(), example.get("value")).isEmpty()).as(id).isEqualTo(valid);
    }

    static Stream<Arguments> positiveRequests() {
        return TomContract.group("schemaPositive").stream().filter(value -> Set.of("CreateDraftRequest", "ReplaceDraftRequest").contains(value.get("schema").asString()))
                .map(value -> Arguments.of(value.get("id").asString(), value));
    }

    @ParameterizedTest(name = "codec accepts preserved request {0}")
    @MethodSource("positiveRequests")
    void acceptsTheSixOriginalPositiveRequestBodies(String id, JsonNode example) {
        if (example.get("schema").asString().equals("ReplaceDraftRequest")) {
            var replacement = DraftCodec.replace(example.get("value"));
            assertThat(replacement.expectedVersion()).isZero();
            TomContract.assertSchema("DraftData", replacement.draft());
        } else TomContract.assertSchema("DraftData", DraftCodec.create(example.get("value")));
    }

    static Stream<Arguments> negativeRequests() {
        return TomContract.group("schemaNegative").stream().filter(value -> Set.of("CreateDraftRequest", "ReplaceDraftRequest").contains(value.get("schema").asString()))
                .map(value -> Arguments.of(value.get("id").asString(), value));
    }

    @ParameterizedTest(name = "codec rejects preserved request {0}")
    @MethodSource("negativeRequests")
    void rejectsTheFifteenOriginalNegativeRequestBodies(String id, JsonNode example) {
        assertThatThrownBy(() -> {
            if (example.get("schema").asString().equals("ReplaceDraftRequest")) DraftCodec.replace(example.get("value"));
            else DraftCodec.create(example.get("value"));
        }).isInstanceOfSatisfying(TomDomainException.class, failure -> {
            assertThat(failure.status()).isEqualTo(400); assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(failure.fieldErrors()).isNotEmpty();
        });
    }

    static Stream<Arguments> originalGraphs() {
        return TomContract.group("graphCases").stream().map(value -> Arguments.of(value.get("id").asString(), value));
    }

    @ParameterizedTest(name = "preserved graph {0}")
    @MethodSource("originalGraphs")
    void validatesAllElevenOriginalSameOrderGraphCases(String id, JsonNode example) {
        if (example.get("expected").asString().equals("valid_graph")) DraftCodec.create(example.get("draft"));
        else assertThatThrownBy(() -> DraftCodec.create(example.get("draft"))).isInstanceOfSatisfying(TomDomainException.class,
                failure -> { assertThat(failure.status()).isEqualTo(400); assertThat(failure.code()).isEqualTo("VALIDATION_ERROR"); });
    }

    @Test
    void graphReferencesAreCaseSensitivePerCollectionAndCanCrossItemsWithinTheSameDraft() {
        ObjectNode draft = minimal();
        draft.set("items", DraftCodec.parseJson("[{\"clientRef\":\"same\",\"sequence\":1},{\"clientRef\":\"Second\",\"sequence\":2}]"));
        draft.set("tasks", DraftCodec.parseJson("[{\"clientRef\":\"same\",\"itemRef\":\"same\"},{\"clientRef\":\"next\",\"itemRef\":\"Second\",\"predecessorRefs\":[\"same\"]}]"));
        assertThat(DraftCodec.create(draft).get("tasks").size()).isEqualTo(2);
        ((ObjectNode) draft.get("tasks").get(1)).put("itemRef", "second");
        assertThatThrownBy(() -> DraftCodec.create(draft)).isInstanceOf(TomDomainException.class);
    }

    @Test
    void mathematicalJsonIntegersAcceptDecimalExponentAndNegativeZeroWithoutLossyConversions() {
        for (String zero : List.of("0", "0.0", "0e0", "-0", "-0.000")) {
            var replacement = DraftCodec.replace(DraftCodec.parseJson("{\"expectedVersion\":" + zero + ",\"draft\":" + minimal() + "}"));
            assertThat(replacement.expectedVersion()).isZero();
        }
        for (String one : List.of("1", "1.0", "1e0")) {
            ObjectNode draft = minimal();
            draft.set("items", DraftCodec.parseJson("[{\"clientRef\":\"item\",\"sequence\":" + one + ",\"packageQty\":" + one + "}]"));
            assertThat(DraftCodec.create(draft).get("items").get(0).get("packageQty").asLong()).isEqualTo(1);
        }
        for (String invalid : List.of("0.5", "9007199254740992", "9007199254740991.0000000000000001", "1e10000000", "1e-10000000")) {
            assertThatThrownBy(() -> DraftCodec.replace(DraftCodec.parseJson("{\"expectedVersion\":" + invalid + ",\"draft\":" + minimal() + "}")))
                    .isInstanceOf(TomDomainException.class);
        }
    }

    @Test
    void canonicalHashMatchesThePreservedRawJsonRulesRatherThanNormalizedDomainDefaults() throws Exception {
        String first = "{\"b\":[{\"z\":null,\"a\":1e0}],\"a\":\" e\\u0301 \"}";
        String canonical = "{\"a\":\" é \",\"b\":[{\"a\":1,\"z\":null}]}";
        assertThat(DraftCodec.canonicalHash(DraftCodec.parseJson(first))).isEqualTo(sha256(canonical));
        assertThat(DraftCodec.canonicalHash(DraftCodec.parseJson("{\"a\":\" e\\u0301 \",\"b\":[{\"a\":1.0,\"z\":null}]}")))
                .isEqualTo(DraftCodec.canonicalHash(DraftCodec.parseJson(first)));
        assertThat(DraftCodec.canonicalHash(DraftCodec.parseJson("{\"v\":-0.0}"))).isEqualTo(sha256("{\"v\":0}"));
        assertThat(DraftCodec.canonicalHash(DraftCodec.parseJson("{\"𐀀\":2,\"\":1}"))).isEqualTo(sha256("{\"\":1,\"𐀀\":2}"));
        for (String[] pair : List.of(new String[] {"{}", "{\"remark\":null}"}, new String[] {"{\"v\":\"é\"}", "{\"v\":\"e\\u0301\"}"},
                new String[] {"{\"v\":\"1.0\"}", "{\"v\":\"1.00\"}"}, new String[] {"{\"v\":[1,2]}", "{\"v\":[2,1]}"},
                new String[] {"{\"expectedVersion\":0}", "{\"expectedVersion\":1}"})) {
            assertThat(DraftCodec.canonicalHash(DraftCodec.parseJson(pair[0]))).isNotEqualTo(DraftCodec.canonicalHash(DraftCodec.parseJson(pair[1])));
        }
    }

    @Test
    void rawJsonRejectsDuplicatePropertiesAndInvalidSyntaxBeforeHashOrPersistence() {
        for (String invalid : List.of("{\"type\":\"IMPORT\",\"type\":\"LOCAL\"}", "{\"items\":[{\"clientRef\":\"a\",\"clientRef\":\"b\"}]}",
                "{} {}", "{", "{\"a\":NaN}")) {
            assertThatThrownBy(() -> DraftCodec.parseJson(invalid)).isInstanceOf(TomDomainException.class);
        }
    }

    @Test
    void textPreservesExactWhitespaceAndUnicodeWhileBlankNonNullableValuesAreRejected() {
        ObjectNode draft = minimal(); draft.put("customerReference", " e\u0301 "); draft.put("remark", "");
        ObjectNode normalized = DraftCodec.create(draft);
        assertThat(normalized.get("customerReference").asString()).isEqualTo(" e\u0301 ");
        assertThat(normalized.get("remark").asString()).isEmpty();
        draft.put("remark", "\u0000合成\u0000");
        assertThat(DraftCodec.create(draft).get("remark").asString()).isEqualTo("\u0000合成\u0000");
        for (String surrogate : List.of("\ud800", "\udc00")) {
            draft.put("remark", surrogate);
            assertThatThrownBy(() -> DraftCodec.create(draft)).isInstanceOf(TomDomainException.class);
        }
        draft.put("remark", "");
        for (String blank : List.of(" ", "\u00a0\u202f", "\ufeff")) {
            draft.put("customerReference", blank);
            assertThatThrownBy(() -> DraftCodec.create(draft)).isInstanceOf(TomDomainException.class);
        }
        draft.put("customerReference", "😀".repeat(200)); assertThat(DraftCodec.create(draft).get("customerReference").asString()).isEqualTo("😀".repeat(200));
        draft.put("customerReference", "😀".repeat(201)); assertThatThrownBy(() -> DraftCodec.create(draft)).isInstanceOf(TomDomainException.class);
    }

    @Test
    void dateTimesSupportTheWholeContractOffsetRangeAndRejectRoundingOrInvalidCalendars() {
        assertThat(DraftCodec.parseInstant("2026-01-02T00:00:00+23:59")).isEqualTo(Instant.parse("2026-01-01T00:01:00Z"));
        assertThat(DraftCodec.parseInstant("2026-01-02t00:00:00-23:59")).isEqualTo(Instant.parse("2026-01-02T23:59:00Z"));
        assertThat(DraftCodec.parseInstant("2026-12-01T08:00:00.123456+08:00")).isEqualTo(Instant.parse("2026-12-01T00:00:00.123456Z"));
        for (String invalid : List.of("2026-02-30T00:00:00Z", "2026-01-01T23:59:60Z", "2026-01-01T00:00:00+24:00",
                "2026-01-01T00:00:00+08:60", "2026-01-01T00:00:00.1234567Z", "2026-01-01T00:00:00")) {
            ObjectNode draft = minimal(); draft.set("shipping", DraftCodec.parseJson("{}")); ((ObjectNode) draft.get("shipping")).put("eta", invalid);
            assertThatThrownBy(() -> DraftCodec.create(draft)).isInstanceOf(TomDomainException.class);
        }
    }

    @Test
    void originalCompleteReadExamplesCheckOnlyTheThreeConfirmedRulesWithoutMutatingOrGrantingAcceptance() {
        for (String id : List.of("DEMO-IMPORT-CONTRACT-response", "DEMO-EXPORT-CONTRACT-response", "DEMO-LOCAL-CONTRACT-response")) {
            JsonNode order = TomContract.example("schemaPositive", id).get("value").get("data"); JsonNode before = order.deepCopy();
            JsonNode checked = AcceptanceChecker.check(order, true);
            TomContract.assertSchema("AcceptanceCheck", checked);
            assertThat(checked.get("confirmedRulesOutcome").asString()).isEqualTo("COMPLETE");
            assertThat(checked.get("missingFields").size()).isZero(); assertThat(checked.get("operationalAcceptance").asString()).isEqualTo("NOT_GRANTED");
            assertThat(order).isEqualTo(before);
        }
    }

    @Test
    void acceptanceReportsFieldDictionaryPointersAndCustomerEligibilityAgainstTheSameVersion() {
        ObjectNode order = (ObjectNode) TomContract.example("schemaPositive", "DEMO-LOCAL-CONTRACT-response").get("value").get("data");
        order.put("version", 17); ((ObjectNode) order.get("items").get(0)).putNull("packageQty");
        JsonNode checked = AcceptanceChecker.check(order, false); TomContract.assertSchema("AcceptanceCheck", checked);
        assertThat(checked.get("version").asLong()).isEqualTo(17); assertThat(checked.get("confirmedRulesOutcome").asString()).isEqualTo("INCOMPLETE");
        Set<String> fieldIds = TomContract.fieldIds(); assertThat(fieldIds).hasSize(40);
        boolean customer = false, quantity = false;
        for (JsonNode issue : checked.get("missingFields")) {
            assertThat(fieldIds.contains(issue.get("fieldId").asString()) || issue.get("fieldId").asString().equals("ORD-P02")).isTrue();
            assertThat(issue.get("path").asString()).startsWith("/");
            customer |= issue.get("fieldId").asString().equals("OF04") && issue.get("ruleId").asString().equals("ORD-P01");
            quantity |= issue.get("fieldId").asString().equals("IF06") && issue.get("path").asString().equals("/items/0/packageQty");
        }
        assertThat(customer).isTrue(); assertThat(quantity).isTrue();
    }

    private ObjectNode minimal() { return (ObjectNode) DraftCodec.parseJson("{\"type\":\"LOCAL\",\"customerId\":\"00000000-0000-4000-8000-000000000103\"}"); }
    private String sha256(String canonical) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
}
