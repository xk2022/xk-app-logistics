package com.xksystem.logistics.upms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.support.MutableTestClock;
import com.xksystem.logistics.support.UpmsTestData;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SecurityPolicyTest {
    @Test
    void hashesUseRandomSixteenByteSaltsAndSixHundredThousandPbkdf2Sha256Iterations() throws Exception {
        var encoder = new UpmsConfiguration().passwordEncoder();
        String password = UpmsTestData.password();
        String encoded = encoder.encode(password);
        String prefix = "{pbkdf2-sha256-v1}";
        assertThat(encoded).startsWith(prefix);
        byte[] bytes = HexFormat.of().parseHex(encoded.substring(prefix.length()));
        assertThat(bytes).hasSize(16 + 32);
        byte[] salt = Arrays.copyOfRange(bytes, 0, 16);
        var key = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        try {
            byte[] independentlyDerived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();
            assertThat(Arrays.equals(independentlyDerived, Arrays.copyOfRange(bytes, 16, bytes.length))).isTrue();
        } finally {
            key.clearPassword();
        }
        assertThat(encoder.encode(password)).isNotEqualTo(encoded);
        assertThat(encoder.matches(password, encoded)).isTrue();
        assertThat(encoder.matches(UpmsTestData.password(), encoded)).isFalse();
    }

    @Test
    void passwordPolicyCountsUnicodeCodePointsNormalizesNfcAndRejectsUnpairedSurrogates() throws Exception {
        PasswordPolicy policy = new PasswordPolicy();
        assertThat(policy.validate("é".repeat(15), "password")).isEqualTo("é".repeat(15));
        assertThat(policy.validate("e\u0301".repeat(15), "password")).isEqualTo("é".repeat(15));
        assertThat(policy.validate("😀".repeat(128), "password")).isEqualTo("😀".repeat(128));
        for (String invalid : new String[] {"é".repeat(14), "😀".repeat(129), "e\u0301".repeat(14),
                "synthetic-long-\uD800", "synthetic-long-\uDC00", "a".repeat(513)}) {
            assertThatThrownBy(() -> policy.validate(invalid, "password")).isInstanceOf(UpmsException.class);
        }
        assertThatThrownBy(() -> policy.normalize("synthetic-long-\uD800")).isInstanceOf(UpmsException.class);
        assertThatThrownBy(() -> policy.validate("passwordpassword", "password")).isInstanceOf(UpmsException.class);
    }

    @Test
    void loginLimiterKeepsKnownLockDuringIdentityFloodAndBoundsRetainedKeys() {
        MutableTestClock clock = new MutableTestClock();
        LoginThrottle limiter = new LoginThrottle(clock);
        for (int index = 0; index < 9; index++) limiter.failure("known-account");
        assertThat(limiter.locked("known-account")).isFalse();
        limiter.failure("known-account");
        assertThat(limiter.locked("known-account")).isTrue();
        for (int index = 0; index < 10_200; index++) limiter.failure("synthetic-identity-" + index);
        assertThat(limiter.locked("known-account")).isTrue();
        assertThat(limiter.locked("new-identity-at-capacity")).isTrue();
        var retained = (Map<?, ?>) ReflectionTestUtils.getField(limiter, "attempts");
        assertThat(retained).isNotNull().hasSizeLessThanOrEqualTo(10_000);
        assertThat(retained.keySet().stream().anyMatch(key -> key.equals("known-account"))).isFalse();
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(limiter.locked("known-account")).isFalse();
        assertThat(limiter.locked("new-identity-at-capacity")).isFalse();
    }

    @Test
    void loginFailureWindowAndClearDoNotKeepStaleCounters() {
        MutableTestClock clock = new MutableTestClock();
        LoginThrottle limiter = new LoginThrottle(clock);
        for (int index = 0; index < 9; index++) limiter.failure("synthetic-account");
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        limiter.failure("synthetic-account");
        assertThat(limiter.locked("synthetic-account")).isFalse();
        for (int index = 0; index < 9; index++) limiter.failure("synthetic-account");
        assertThat(limiter.locked("synthetic-account")).isTrue();
        limiter.clear("synthetic-account");
        assertThat(limiter.locked("synthetic-account")).isFalse();
    }
}
