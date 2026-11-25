package com.xksystem.logistics.upms;

import static org.assertj.core.api.Assertions.assertThat;

import com.xksystem.logistics.support.UpmsTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(OutputCaptureExtension.class)
class UpmsErrorsTest {
    @Test
    void internalAndDatabaseErrorsExposeNeitherSubmittedSecretsNorCauses(CapturedOutput output) {
        String secret = UpmsTestData.password();
        UpmsErrors handler = new UpmsErrors();
        var unexpected = handler.unexpected(new IllegalStateException(secret, new RuntimeException(secret)));
        assertThat(unexpected.getStatusCode().value()).isEqualTo(500);
        assertThat(unexpected.getBody()).isNotNull();
        assertThat(unexpected.getBody().code()).isEqualTo("E0500");
        assertThat(unexpected.getBody().data()).isNull();
        assertThat(unexpected.getBody().toString().contains(secret)).isFalse();
        var unavailable = handler.unavailable(new DataAccessResourceFailureException(secret));
        assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
        assertThat(unavailable.getBody()).isNotNull();
        assertThat(unavailable.getBody().code()).isEqualTo("E0503");
        assertThat(unavailable.getBody().toString().contains(secret)).isFalse();
        assertThat(output.toString().contains(secret)).isFalse();
        assertThat(output.toString()).contains("UPMS request failed (IllegalStateException)");
    }
}
