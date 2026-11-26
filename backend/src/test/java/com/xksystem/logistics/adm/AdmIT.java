package com.xksystem.logistics.adm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.support.AdmTestData;
import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.UpmsTestData;
import com.xksystem.logistics.upms.UpmsException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTPS, session/CSRF filters, transactions and PostgreSQL constraints for ADM. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdmIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.12-bookworm@sha256:1eff563f6c868b9b433566e9f3cf6e5c79fbd3b95bf70ec7277d376cdbfc50d7")
            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        TlsTestSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder encoder;
    @Autowired AdmQueries queries;
    @Autowired PlatformTransactionManager transactionManager;
    private final JsonMapper json = JsonMapper.builder().build();
    private Map<String, String> keys;

    @BeforeEach
    void arrangeOnlyIsolatedSyntheticData() throws Exception {
        jdbc.execute("TRUNCATE TABLE logistics.tms_execution_command,logistics.tms_execution_event,logistics.tms_execution,logistics.tms_resource_reservation,logistics.tms_dispatch_event,logistics.tms_idempotency,logistics.tms_assignment,logistics.tms_task_dispatch,logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle, logistics.order_core, logistics.tom_order, logistics.cargo_item, logistics.transport_task, "
                + "logistics.task_dependency, logistics.order_draft_revision, logistics.api_idempotency, "
                + "logistics.crm_contact, logistics.crm_address, logistics.crm_customer, "
                + "logistics.adm_change_log, logistics.adm_parameter, logistics.adm_parameter_definition, "
                + "logistics.upms_audit, logistics.upms_account");
        jdbc.update("DELETE FROM logistics.adm_dict_item WHERE NOT read_only");
        jdbc.update("DELETE FROM logistics.adm_dict_category WHERE code NOT IN ('ORDER_TYPE','CONTAINER_TYPE','TASK_KIND','VEHICLE_TYPE','DRIVER_LICENSE_TYPE')");
        jdbc.update("UPDATE logistics.adm_dict_category SET enabled=true, version=0 WHERE NOT read_only");
        try (var connection = dataSource.getConnection()) {
            keys = AdmTestData.insertDefinitions(connection, "IT");
        }
    }

    @Test
    void everyAdmRouteRequiresAnUnrestrictedAdministratorAndWritesRequireCsrf() throws Exception {
        Browser anonymous = new Browser();
        for (String path : List.of("/adm/categories", "/adm/parameters", "/adm/parameter-definitions", "/adm/change-logs",
                "/adm/options/ORDER_TYPE")) {
            assertResponse(anonymous.get(path), 401, "E0401");
        }
        assertResponse(anonymous.post("/adm/categories", categoryBody(code()), true), 401, "E0401");
        for (String role : List.of("DISPATCHER", "DRIVER")) {
            Browser worker = login(role, false);
            for (String path : List.of("/adm/categories", "/adm/parameters", "/adm/parameter-definitions", "/adm/change-logs",
                    "/adm/options/ORDER_TYPE")) {
                assertResponse(worker.get(path), 403, "E0403");
            }
            assertResponse(worker.post("/adm/categories", categoryBody(code()), true), 403, "E0403");
        }
        Browser temporaryAdmin = login("ADMIN", true);
        assertResponse(temporaryAdmin.get("/adm/categories"), 403, "E0403");
        assertResponse(temporaryAdmin.post("/adm/categories", categoryBody(code()), true), 403, "E0403");
        Browser admin = login("ADMIN", false);
        assertResponse(admin.post("/adm/categories", categoryBody(code()), false), 403, "E0403");
        JsonNode category = createCategory(admin, code());
        assertResponse(admin.send("PATCH", "/adm/categories/" + category.get("id").asString(), categoryPatch(category, true), null),
                403, "E0403");
    }

    @Test
    void fixedOrderTypesAreReadOnlyAndOtherInitialCategoriesHaveNoInventedBusinessItems() throws Exception {
        Browser admin = login("ADMIN", false);
        JsonNode categories = page(admin.get("/adm/categories"));
        assertThat(codes(categories)).containsExactly("ORDER_TYPE", "CONTAINER_TYPE", "TASK_KIND", "VEHICLE_TYPE", "DRIVER_LICENSE_TYPE");
        JsonNode orderType = categories.get(0);
        assertThat(orderType.get("readOnly").asBoolean()).isTrue();
        JsonNode items = page(admin.get("/adm/categories/" + orderType.get("id").asString() + "/items"));
        assertThat(codes(items)).containsExactly("IMPORT", "EXPORT", "LOCAL");
        for (JsonNode item : items) {
            assertThat(item.get("readOnly").asBoolean()).isTrue();
            assertResponse(admin.patch("/adm/items/" + item.get("id").asString(), itemPatch(item, false, 99)), 400, "E0400");
        }
        assertResponse(admin.patch("/adm/categories/" + orderType.get("id").asString(), categoryPatch(orderType, false)), 400, "E0400");
        assertResponse(admin.post("/adm/categories/" + orderType.get("id").asString() + "/items", itemBody("SYNTHETIC", true, 0), true),
                400, "E0400");
        for (int index = 1; index < 4; index++) {
            assertThat(page(admin.get("/adm/categories/" + categories.get(index).get("id").asString() + "/items")).size()).isZero();
        }
        assertThat(count("SELECT count(*) FROM logistics.adm_parameter")).isZero();
        assertThat(count("SELECT count(*) FROM logistics.adm_change_log")).isZero();
    }

    @Test
    void categoryAndItemCodesAreUniqueWithinTheirDefinedScopeAndForeignKeysAreEnforced() throws Exception {
        Browser admin = login("ADMIN", false);
        String categoryCode = code();
        JsonNode first = createCategory(admin, categoryCode);
        assertResponse(admin.post("/adm/categories", categoryBody(categoryCode), true), 409, "E0409");
        JsonNode second = createCategory(admin, code());
        createItem(admin, first, "SAME_CODE", true, 10);
        assertResponse(admin.post("/adm/categories/" + first.get("id").asString() + "/items", itemBody("SAME_CODE", true, 20), true),
                409, "E0409");
        createItem(admin, second, "SAME_CODE", true, 10);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO logistics.adm_dict_item
                    (id,category_id,code,label,enabled,sort_order,read_only,version,created_at,updated_at)
                VALUES (?,?,'SYNTHETIC_ORPHAN','合成孤立項目',true,0,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("SELECT count(*) FROM logistics.adm_dict_item WHERE code='SYNTHETIC_ORPHAN'")).isZero();
        assertResponse(admin.post("/adm/categories/" + UUID.randomUUID() + "/items", itemBody("MISSING_PARENT", true, 1), true),
                404, "E0404");
    }

    @Test
    void immutableIdentityAndSystemFieldsAreRejectedAndNoHardDeleteEndpointExists() throws Exception {
        Browser admin = login("ADMIN", false);
        JsonNode category = createCategory(admin, code());
        JsonNode item = createItem(admin, category, "IMMUTABLE_ITEM", true, 0);
        Map<String, Object> forgedCreate = new LinkedHashMap<>(categoryBody(code()));
        forgedCreate.put("readOnly", true);
        assertResponse(admin.post("/adm/categories", forgedCreate, true), 400, "E0400");
        for (String field : List.of("id", "code", "readOnly", "createdAt", "updatedAt")) {
            Map<String, Object> patch = new LinkedHashMap<>(categoryPatch(category, true));
            patch.put(field, field.equals("readOnly") ? true : "SYNTHETIC_FORBIDDEN");
            assertResponse(admin.patch("/adm/categories/" + category.get("id").asString(), patch), 400, "E0400");
        }
        for (String field : List.of("id", "code", "categoryId", "readOnly")) {
            Map<String, Object> patch = new LinkedHashMap<>(itemPatch(item, true, 0));
            patch.put(field, field.equals("readOnly") ? true : "SYNTHETIC_FORBIDDEN");
            assertResponse(admin.patch("/adm/items/" + item.get("id").asString(), patch), 400, "E0400");
        }
        JsonNode parameter = createParameter(admin, keys.get("text"), "TEXT", "valid text");
        for (String field : List.of("id", "key", "valueType", "createdAt")) {
            Map<String, Object> patch = new LinkedHashMap<>(parameterPatch(parameter, "new text", true));
            patch.put(field, "SYNTHETIC_FORBIDDEN");
            assertResponse(admin.patch("/adm/parameters/" + parameter.get("id").asString(), patch), 400, "E0400");
        }
        assertResponse(admin.patch("/adm/items/" + item.get("id").asString(), itemPatch(item, false, 3)), 200, "0000");
        assertResponse(admin.patch("/adm/items/" + item.get("id").asString(), itemPatch(item, true, 4)), 409, "E0409");
        assertResponse(admin.patch("/adm/parameters/" + parameter.get("id").asString(), parameterPatch(parameter, "new text", false)), 200, "0000");
        assertResponse(admin.patch("/adm/parameters/" + parameter.get("id").asString(), parameterPatch(parameter, "older text", true)), 409, "E0409");
        for (String path : List.of("/adm/categories/" + category.get("id").asString(),
                "/adm/items/" + item.get("id").asString(), "/adm/parameters/" + parameter.get("id").asString())) {
            assertThat(admin.send("DELETE", path, null, admin.csrf()).statusCode()).isEqualTo(405);
        }
        assertThat(count("SELECT count(*) FROM logistics.adm_dict_category WHERE id='" + category.get("id").asString() + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM logistics.adm_dict_item WHERE id='" + item.get("id").asString() + "'"))
                .isEqualTo(1);
    }

    @Test
    void enabledOptionsUseStableOrderAndDisablingParentOrItemPreservesHistoricalLabels() throws Exception {
        Browser admin = login("ADMIN", false);
        String categoryCode = code();
        JsonNode category = createCategory(admin, categoryCode);
        JsonNode b = createItem(admin, category, "BETA", true, 10);
        JsonNode a = createItem(admin, category, "ALPHA", true, 10);
        createItem(admin, category, "LATER", true, 20);
        createItem(admin, category, "DISABLED", false, 0);
        assertThat(codes(data(admin.get("/adm/options/" + categoryCode)))).containsExactly("ALPHA", "BETA", "LATER");
        assertThat(queries.enabledOptions(categoryCode).stream().map(AdmDtos.Item::code).toList())
                .containsExactly("ALPHA", "BETA", "LATER");
        assertResponse(admin.patch("/adm/items/" + b.get("id").asString(), itemPatch(b, false, 10)), 200, "0000");
        assertThat(queries.enabledOptions(categoryCode).stream().map(AdmDtos.Item::code).toList()).containsExactly("ALPHA", "LATER");
        assertThat(queries.historicalLabel(categoryCode, "BETA")).contains("合成 BETA");
        assertThat(queries.historicalLabel(UUID.fromString(b.get("id").asString()))).contains("合成 BETA");
        assertResponse(admin.patch("/adm/categories/" + category.get("id").asString(), categoryPatch(category, false)), 200, "0000");
        assertThat(data(admin.get("/adm/options/" + categoryCode)).size()).isZero();
        assertThat(queries.enabledOptions(categoryCode)).isEmpty();
        assertThat(queries.historicalLabel(UUID.fromString(a.get("id").asString()))).contains("合成 ALPHA");
        assertResponse(admin.patch("/adm/items/" + a.get("id").asString(), itemPatch(a, true, 5)), 200, "0000");
        assertThat(queries.enabledOptions(categoryCode)).isEmpty();
    }

    @Test
    void optionQueryRechecksParentInItsFinalStatementEvenWithAnAlreadyManagedParentEntity() throws Exception {
        Browser admin = login("ADMIN", false);
        String categoryCode = code();
        JsonNode category = createCategory(admin, categoryCode);
        createItem(admin, category, "SYNTHETIC_READY", true, 0);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertThat(queries.enabledOptions(categoryCode)).hasSize(1);
            try (var outside = dataSource.getConnection();
                    var statement = outside.prepareStatement("UPDATE logistics.adm_dict_category SET enabled=false WHERE id=?")) {
                assertThat(outside.getAutoCommit()).isTrue();
                statement.setObject(1, UUID.fromString(category.get("id").asString()));
                assertThat(statement.executeUpdate()).isEqualTo(1);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not arrange the separate committed parent change", failure);
            }
            assertThat(queries.enabledOptions(categoryCode)).isEmpty();
        });
    }

    @Test
    void simultaneousUpdatesCannotSilentlyOverwriteEachOtherOrCreateExtraAuditRecords() throws Exception {
        Browser admin = login("ADMIN", false);
        JsonNode category = createCategory(admin, code());
        String token = admin.csrf();
        long beforeAudit = count("SELECT count(*) FROM logistics.adm_change_log");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Integer>>of(
                    () -> admin.send("PATCH", "/adm/categories/" + category.get("id").asString(),
                            Map.of("name", "合成競爭甲", "enabled", true, "sortOrder", 1, "version", 0), token).statusCode(),
                    () -> admin.send("PATCH", "/adm/categories/" + category.get("id").asString(),
                            Map.of("name", "合成競爭乙", "enabled", true, "sortOrder", 2, "version", 0), token).statusCode()));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM logistics.adm_dict_category WHERE id=?", Long.class,
                UUID.fromString(category.get("id").asString()))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM logistics.adm_change_log")).isEqualTo(beforeAudit + 1);
    }

    @Test
    void parametersRequireApprovedDefinitionsAndValidateTypeBoundsAndSecretKeyExclusions() throws Exception {
        Browser admin = login("ADMIN", false);
        JsonNode definitions = data(admin.get("/adm/parameter-definitions"));
        assertThat(definitions.size()).isEqualTo(3);
        for (String key : List.of("UNAPPROVED_BUSINESS_KEY", "DB_PASSWORD", "SESSION_TIMEOUT", "JWT_SECRET", "CORS_ORIGIN")) {
            assertResponse(admin.post("/adm/parameters", parameterBody(key, "TEXT", "synthetic"), true), 400, "E0400");
        }
        for (String value : List.of("X", "x".repeat(13))) {
            assertResponse(admin.post("/adm/parameters", parameterBody(keys.get("text"), "TEXT", value), true), 400, "E0400");
        }
        assertResponse(admin.post("/adm/parameters", parameterBody(keys.get("text"), "BOOLEAN", "true"), true), 400, "E0400");
        JsonNode text = createParameter(admin, keys.get("text"), "TEXT", "合成文字");
        assertResponse(admin.post("/adm/parameters", parameterBody(keys.get("text"), "TEXT", "another"), true), 409, "E0409");
        for (String value : List.of("-1", "100.01", "1.001", "NaN", "Infinity", "not-a-number")) {
            assertResponse(admin.post("/adm/parameters", parameterBody(keys.get("number"), "NUMBER", value), true), 400, "E0400");
        }
        JsonNode number = createParameter(admin, keys.get("number"), "NUMBER", "12.50");
        assertThat(new java.math.BigDecimal(number.get("valueText").asString())).isEqualByComparingTo("12.5");
        for (String value : List.of("yes", "1", "TRUE", "")) {
            assertResponse(admin.post("/adm/parameters", parameterBody(keys.get("boolean"), "BOOLEAN", value), true), 400, "E0400");
        }
        JsonNode bool = createParameter(admin, keys.get("boolean"), "BOOLEAN", "true");
        assertThat(bool.get("valueText").asString()).isEqualTo("true");
        assertThat(queries.enabledParameter(keys.get("text"))).isPresent();
        assertResponse(admin.patch("/adm/parameters/" + text.get("id").asString(), parameterPatch(text, "改後文字", false)), 200, "0000");
        assertThat(queries.enabledParameter(keys.get("text"))).isEmpty();
        assertThat(queries.enabledParameter("UNAPPROVED_BUSINESS_KEY")).isEmpty();
        assertThat(count("SELECT count(*) FROM logistics.adm_parameter")).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO logistics.adm_parameter(id,parameter_key,name,value_type,value_text,description,enabled,version,created_at,updated_at)
                VALUES (?,'SYNTHETIC_ORPHAN','合成','TEXT','x','合成',true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void textEnumerationsAndDefinitionGovernanceCannotBeChangedThroughTheHttpApi() throws Exception {
        Browser admin = login("ADMIN", false);
        String enumKey = "SYNTHETIC_ENUM_IT";
        try (var connection = dataSource.getConnection()) {
            AdmTestData.insertTextEnumeration(connection, enumKey);
        }
        assertResponse(admin.post("/adm/parameters", parameterBody(enumKey, "TEXT", "GAMMA"), true), 400, "E0400");
        createParameter(admin, enumKey, "TEXT", "ALPHA");
        assertThat(admin.post("/adm/parameter-definitions", Map.of("key", "SYNTHETIC_NOT_APPROVED"), true).statusCode()).isEqualTo(405);
        assertThat(admin.send("PATCH", "/adm/parameter-definitions/" + enumKey, Map.of("name", "changed"), admin.csrf()).statusCode())
                .isIn(404, 405);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO logistics.adm_parameter_definition(parameter_key,name,value_type,description,min_length,max_length)
                VALUES ('SYNTHETIC_PASSWORD','合成','TEXT','不得成為安全設定',1,50)
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void malformedGovernanceValuesCannotBeCoercedToStringsByDefinitionOrSharedQueryReads() throws Exception {
        Browser admin = login("ADMIN", false);
        createParameter(admin, keys.get("text"), "TEXT", "valid text");
        for (String malformed : List.of("[123]", "[true]", "[\"ALPHA\",\"ALPHA\"]", "[\"token=synthetic\"]")) {
            jdbc.update("""
                    UPDATE logistics.adm_parameter_definition
                    SET min_length=NULL,max_length=NULL,allowed_values=?::jsonb WHERE parameter_key=?
                    """, malformed, keys.get("text"));
            assertResponse(admin.get("/adm/parameter-definitions"), 400, "E0400");
            assertGovernanceRejected(() -> queries.enabledParameter(keys.get("text")));
        }
    }

    @Test
    void sharedParameterQueriesRevalidateLatestBoundsCredentialsAndCanonicalForm() throws Exception {
        Browser admin = login("ADMIN", false);
        createParameter(admin, keys.get("text"), "TEXT", "abcdef");
        createParameter(admin, keys.get("number"), "NUMBER", "12.5");
        createParameter(admin, keys.get("boolean"), "BOOLEAN", "true");
        jdbc.update("UPDATE logistics.adm_parameter_definition SET max_length=3 WHERE parameter_key=?", keys.get("text"));
        assertGovernanceRejected(() -> queries.enabledParameter(keys.get("text")));
        jdbc.update("UPDATE logistics.adm_parameter_definition SET max_value=10 WHERE parameter_key=?", keys.get("number"));
        assertGovernanceRejected(() -> queries.enabledParameter(keys.get("number")));
        jdbc.update("UPDATE logistics.adm_parameter SET value_text='TRUE' WHERE parameter_key=?", keys.get("boolean"));
        assertGovernanceRejected(() -> queries.enabledParameter(keys.get("boolean")));
        jdbc.update("UPDATE logistics.adm_parameter_definition SET max_length=12 WHERE parameter_key=?", keys.get("text"));
        jdbc.update("UPDATE logistics.adm_parameter SET value_text='token=demo' WHERE parameter_key=?", keys.get("text"));
        assertGovernanceRejected(() -> queries.enabledParameter(keys.get("text")));
        jdbc.update("UPDATE logistics.adm_parameter_definition SET max_value=100 WHERE parameter_key=?", keys.get("number"));
        jdbc.update("UPDATE logistics.adm_parameter SET value_text='12.50' WHERE parameter_key=?", keys.get("number"));
        assertGovernanceRejected(() -> queries.enabledParameter(keys.get("number")));
    }

    @Test
    void changeLogsCaptureBeforeAfterAndAuditFailureRollsBackTheBusinessMutation() throws Exception {
        Browser admin = login("ADMIN", false);
        JsonNode category = createCategory(admin, code());
        String id = category.get("id").asString();
        assertResponse(admin.patch("/adm/categories/" + id, categoryPatch(category, false)), 200, "0000");
        JsonNode logs = page(admin.get("/adm/change-logs?size=100"));
        assertThat(logs.size()).isEqualTo(2);
        JsonNode update = null;
        JsonNode create = null;
        for (JsonNode log : logs) {
            assertThat(log.get("actorId").asString()).isNotBlank();
            assertThat(log.get("actorUsername").asString()).startsWith("adm-test-");
            assertThat(log.get("targetId").asString()).isEqualTo(id);
            if (log.get("action").asString().equals("UPDATE")) update = log;
            if (log.get("action").asString().equals("CREATE")) create = log;
        }
        assertThat(create).isNotNull();
        assertThat(create.get("before").isNull()).isTrue();
        assertThat(create.get("after").get("code").asString()).isEqualTo(category.get("code").asString());
        assertThat(update).isNotNull();
        assertThat(update.get("before").get("enabled").asBoolean()).isTrue();
        assertThat(update.get("after").get("enabled").asBoolean()).isFalse();
        long before = count("SELECT count(*) FROM logistics.adm_change_log");
        String rejectedCode = code();
        jdbc.execute("""
                CREATE FUNCTION logistics.synthetic_reject_adm_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic audit failure'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER synthetic_reject_adm_audit BEFORE INSERT ON logistics.adm_change_log "
                + "FOR EACH ROW EXECUTE FUNCTION logistics.synthetic_reject_adm_audit()");
        try {
            HttpResponse<String> failed = admin.post("/adm/categories", categoryBody(rejectedCode), true);
            assertThat(failed.statusCode()).isBetween(500, 599);
            assertThat(failed.body()).doesNotContain("synthetic audit failure", "INSERT INTO", "SQLException");
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_reject_adm_audit ON logistics.adm_change_log");
            jdbc.execute("DROP FUNCTION logistics.synthetic_reject_adm_audit()");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.adm_dict_category WHERE code=?", Long.class, rejectedCode)).isZero();
        assertThat(count("SELECT count(*) FROM logistics.adm_change_log")).isEqualTo(before);
    }

    @Test
    void paginationAndSortingRejectInvalidBoundsAndUnknownSortProperties() throws Exception {
        Browser admin = login("ADMIN", false);
        for (String query : List.of("page=-1", "size=0", "size=101", "sort=passwordHash,asc", "sort=code,sideways", "page=2147483648")) {
            assertResponse(admin.get("/adm/categories?" + query), 400, "E0400");
        }
        for (String path : List.of("/adm/parameters?size=101", "/adm/change-logs?page=-1", "/adm/change-logs?sort=actorPassword,asc")) {
            assertResponse(admin.get(path), 400, "E0400");
        }
        JsonNode result = data(admin.get("/adm/categories?page=0&size=2"));
        assertThat(result.get("page").asInt()).isZero();
        assertThat(result.get("size").asInt()).isEqualTo(2);
        assertThat(result.get("totalElements").asLong()).isEqualTo(5);
        assertThat(result.get("totalPages").asInt()).isEqualTo(3);
        assertThat(result.get("content").size()).isEqualTo(2);
        assertThat(page(admin.get("/adm/categories?page=2&size=2")).size()).isEqualTo(1);
        assertThat(page(admin.get("/adm/categories?page=3&size=2")).size()).isZero();
    }

    @Test
    void allSixWriteEndpointsRejectUnknownQueryArgumentsWithoutPerformingMutations() throws Exception {
        Browser admin = login("ADMIN", false);
        JsonNode category = createCategory(admin, code());
        JsonNode item = createItem(admin, category, "SYNTHETIC_QUERY", true, 0);
        JsonNode parameter = createParameter(admin, keys.get("text"), "TEXT", "query text");
        long beforeAudit = count("SELECT count(*) FROM logistics.adm_change_log");
        assertResponse(admin.post("/adm/categories?unknown=synthetic", categoryBody(code()), true), 400, "E0400");
        assertResponse(admin.patch("/adm/categories/" + category.get("id").asString() + "?unknown=synthetic", categoryPatch(category, false)),
                400, "E0400");
        assertResponse(admin.post("/adm/categories/" + category.get("id").asString() + "/items?unknown=synthetic",
                itemBody("SYNTHETIC_NEW_QUERY", true, 0), true), 400, "E0400");
        assertResponse(admin.patch("/adm/items/" + item.get("id").asString() + "?unknown=synthetic", itemPatch(item, false, 1)),
                400, "E0400");
        assertResponse(admin.post("/adm/parameters?unknown=synthetic", parameterBody(keys.get("number"), "NUMBER", "12.5"), true),
                400, "E0400");
        assertResponse(admin.patch("/adm/parameters/" + parameter.get("id").asString() + "?unknown=synthetic",
                parameterPatch(parameter, "changed text", false)), 400, "E0400");
        assertThat(count("SELECT count(*) FROM logistics.adm_change_log")).isEqualTo(beforeAudit);
        assertThat(jdbc.queryForObject("SELECT enabled FROM logistics.adm_dict_category WHERE id=?", Boolean.class,
                UUID.fromString(category.get("id").asString()))).isTrue();
    }

    @Test
    void revokingAnActorWhileTheirWriteWaitsForTheCanonicalLockPreventsBothMutationAndAudit() throws Exception {
        Account account = account("ADMIN", false);
        Browser admin = new Browser();
        assertResponse(admin.login(account), 200, "0000");
        String token = admin.csrf();
        String rejectedCode = code();
        try (var pool = Executors.newSingleThreadExecutor(); var lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close();
            }
            var pending = pool.submit(() -> admin.send("POST", "/adm/categories", categoryBody(rejectedCode), token));
            boolean waiting = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Long waiters = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%upms_lock%'
                        """, Long.class);
                if (waiters != null && waiters > 0) { waiting = true; break; }
                Thread.sleep(20);
            }
            assertThat(waiting).as("ADM write reached and waited for the canonical UPMS row lock").isTrue();
            try (var statement = lock.prepareStatement("UPDATE logistics.upms_account SET auth_version=auth_version+1 WHERE id=?")) {
                statement.setObject(1, account.id());
                statement.executeUpdate();
            }
            lock.commit();
            assertResponse(pending.get(15, TimeUnit.SECONDS), 401, "E0401");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.adm_dict_category WHERE code=?", Long.class, rejectedCode)).isZero();
        assertThat(count("SELECT count(*) FROM logistics.adm_change_log")).isZero();
    }

    @Test
    void admUsesThePublicUpmsBoundaryRatherThanImportingItsInternalRepositoriesOrService() throws Exception {
        Path classes = Path.of("target/classes/com/xksystem/logistics/adm");
        try (var files = Files.walk(classes)) {
            var compiled = files.filter(path -> path.toString().endsWith(".class")).toList();
            assertThat(compiled).isNotEmpty();
            for (Path file : compiled) {
                String constants = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertThat(constants.contains("com/xksystem/logistics/upms/internal/"))
                        .as("ADM bytecode depends only on the public UPMS authorization boundary: " + file.getFileName()).isFalse();
            }
        }
    }

    private String code() { return "SYNTHETIC_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT); }

    private Map<String, Object> categoryBody(String code) {
        return Map.of("code", code, "name", "合成測試分類", "enabled", true, "sortOrder", 100);
    }

    private Map<String, Object> categoryPatch(JsonNode category, boolean enabled) {
        return Map.of("name", "合成更新分類", "enabled", enabled, "sortOrder", 100, "version", category.get("version").asLong());
    }

    private Map<String, Object> itemBody(String code, boolean enabled, int order) {
        return Map.of("code", code, "label", "合成 " + code, "enabled", enabled, "sortOrder", order);
    }

    private Map<String, Object> itemPatch(JsonNode item, boolean enabled, int order) {
        return Map.of("label", item.get("label").asString(), "enabled", enabled, "sortOrder", order,
                "version", item.get("version").asLong());
    }

    private Map<String, Object> parameterBody(String key, String type, String value) {
        return Map.of("key", key, "name", "合成測試參數", "valueType", type, "valueText", value,
                "description", "Isolated synthetic parameter", "enabled", true);
    }

    private Map<String, Object> parameterPatch(JsonNode parameter, String value, boolean enabled) {
        return Map.of("name", "合成更新參數", "valueText", value, "description", "Isolated synthetic parameter",
                "enabled", enabled, "version", parameter.get("version").asLong());
    }

    private JsonNode createCategory(Browser browser, String code) throws Exception {
        HttpResponse<String> response = browser.post("/adm/categories", categoryBody(code), true);
        assertResponse(response, 201, "0000");
        return data(response);
    }

    private JsonNode createItem(Browser browser, JsonNode category, String code, boolean enabled, int order) throws Exception {
        HttpResponse<String> response = browser.post("/adm/categories/" + category.get("id").asString() + "/items", itemBody(code, enabled, order), true);
        assertResponse(response, 201, "0000");
        return data(response);
    }

    private JsonNode createParameter(Browser browser, String key, String type, String value) throws Exception {
        HttpResponse<String> response = browser.post("/adm/parameters", parameterBody(key, type, value), true);
        assertResponse(response, 201, "0000");
        return data(response);
    }

    private Account account(String role, boolean temporary) throws Exception {
        String username = "adm-test-" + UUID.randomUUID().toString().substring(0, 16);
        String password = UpmsTestData.password();
        try (var connection = dataSource.getConnection()) {
            UUID id = UpmsTestData.insert(connection, encoder, username, role, password, temporary, Instant.now());
            return new Account(id, username, password);
        }
    }

    private Browser login(String role, boolean temporary) throws Exception {
        Browser browser = new Browser();
        assertResponse(browser.login(account(role, temporary)), 200, "0000");
        return browser;
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private void assertGovernanceRejected(Runnable query) {
        assertThatThrownBy(query::run).isInstanceOfSatisfying(UpmsException.class,
                failure -> assertThat(failure.status()).isEqualTo(400));
    }
    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).get("data"); }
    private JsonNode page(HttpResponse<String> response) throws Exception { assertResponse(response, 200, "0000"); return data(response).get("content"); }
    private List<String> codes(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) result.add(value.get("code").asString());
        return result;
    }
    private void assertResponse(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("ADM HTTP response status").isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("timestamp").asString()).endsWith("+08:00");
    }
    private record Account(UUID id, String username, String password) {}

    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).sslContext(TlsTestSupport.trustedContext())
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        String csrf() throws Exception {
            HttpResponse<String> response = get("/auth/csrf");
            assertResponse(response, 200, "0000");
            return data(response).get("token").asString();
        }
        HttpResponse<String> login(Account account) throws Exception {
            return post("/auth/login", Map.of("username", account.username(), "password", account.password()), true);
        }
        HttpResponse<String> post(String path, Object body, boolean csrf) throws Exception { return send("POST", path, body, csrf ? csrf() : null); }
        HttpResponse<String> patch(String path, Object body) throws Exception { return send("PATCH", path, body, csrf()); }
        HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
            if (token != null) request.header("X-CSRF-TOKEN", token);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
