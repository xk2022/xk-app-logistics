package com.xksystem.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Flyway flyway;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void initializesEmptyPostgresAndRevalidatesWithoutBusinessTables() {
        assertThat(jdbc.queryForObject("select version from logistics.flyway_schema_history where success and version = '1'", String.class))
                .isEqualTo("1");
        assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname = 'logistics'", String.class))
                .containsExactly("flyway_schema_history");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void exposesOnlySafeHealthAndStatus() throws Exception {
        HttpResponse<String> health = get("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json.readTree(health.body())).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
        assertThat(health.headers().firstValue("X-Request-Id")).isPresent();

        HttpResponse<String> response = get("/api/v1/status");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("0000");
        assertThat(body.get("message").asString()).isEqualTo("成功");
        assertThat(body.get("data").get("service").asString()).isEqualTo("xk-logistics");
        assertThat(body.get("data").get("status").asString()).isEqualTo("UP");
        assertThat(OffsetDateTime.parse(body.get("timestamp").asString()).getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/orders").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
