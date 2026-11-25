package com.xksystem.logistics.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;

class StatusControllerTest {
    @Test
    void returnsServiceUnavailableWithoutHealthDetails() {
        var health = mock(HealthEndpoint.class);
        var descriptor = mock(IndicatedHealthDescriptor.class);
        when(descriptor.getStatus()).thenReturn(Status.DOWN);
        when(health.health()).thenReturn(descriptor);
        var response = new StatusController(health).status();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("E0503");
        assertThat(response.getBody().message()).isEqualTo("服務暫時無法使用，請稍後重試");
        assertThat(response.getBody().data().status()).isEqualTo("DOWN");
        assertThat(response.getBody().timestamp().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    }
}
