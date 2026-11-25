package com.xksystem.logistics.platform;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final HealthEndpoint health;

    public StatusController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping("/api/v1/status")
    public ResponseEntity<StatusResponse> status() {
        boolean available = Status.UP.equals(health.health().getStatus());
        var response = new StatusResponse(
                available ? "0000" : "E0503",
                available ? "成功" : "服務暫時無法使用，請稍後重試",
                new ServiceStatus("xk-logistics", available ? "UP" : "DOWN"),
                OffsetDateTime.now(ZoneOffset.ofHours(8)));
        return ResponseEntity.status(available ? 200 : 503).body(response);
    }

    public record StatusResponse(String code, String message, ServiceStatus data, OffsetDateTime timestamp) {}
    public record ServiceStatus(String service, String status) {}
}
