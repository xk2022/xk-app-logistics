package com.xksystem.logistics.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xksystem.logistics.support.TlsTestSupport;
import com.xksystem.logistics.support.UpmsTestData;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.UpmsAuthorization;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CRM master-data and reference-source tests, not order creation or persisted order-snapshot acceptance. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrmIT {
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
    @Autowired CrmQueries queries;
    @Autowired UpmsAuthorization authorization;
    @Autowired PlatformTransactionManager transactionManager;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void isolateSyntheticCrmAndAccounts() {
        jdbc.execute("TRUNCATE TABLE logistics.order_acceptance_event,logistics.fms_driver,logistics.fms_vehicle, logistics.order_core, logistics.tom_order, logistics.cargo_item, logistics.transport_task, "
                + "logistics.task_dependency, logistics.order_draft_revision, logistics.api_idempotency, "
                + "logistics.crm_contact, logistics.crm_address, logistics.crm_customer, "
                + "logistics.adm_change_log, logistics.upms_audit, logistics.upms_account");
    }

    @Test
    void onlyNormalAdministratorsAndDispatchersManageCrmAndAdmRemainsAdministratorOnly() throws Exception {
        Browser anonymous = new Browser();
        for (String path : List.of("/crm/customers", "/crm/customer-options", "/crm/customers/" + UUID.randomUUID())) {
            assertResponse(anonymous.get(path), 401, "E0401");
        }
        assertResponse(anonymous.post("/crm/customers", customerBody(code(), "合成未登入"), true), 401, "E0401");
        Browser driver = login("DRIVER", false);
        assertResponse(driver.get("/crm/customers"), 403, "E0403");
        assertResponse(driver.get("/crm/customer-options"), 403, "E0403");
        assertResponse(driver.post("/crm/customers", customerBody(code(), "合成拒絕"), true), 403, "E0403");
        for (String role : List.of("ADMIN", "DISPATCHER")) {
            Browser temporary = login(role, true);
            assertResponse(temporary.get("/crm/customers"), 403, "E0403");
            assertResponse(temporary.post("/crm/customers", customerBody(code(), "合成待改密"), true), 403, "E0403");
            Browser manager = login(role, false);
            assertResponse(manager.get("/crm/customers"), 200, "0000");
            assertResponse(manager.post("/crm/customers", customerBody(code(), "合成缺少安全驗證"), false), 403, "E0403");
            JsonNode customer = createCustomer(manager, code(), "合成授權 " + role);
            assertResponse(driver.get("/crm/customers/" + id(customer) + "/contacts"), 403, "E0403");
            assertResponse(driver.get("/crm/customers/" + id(customer) + "/addresses"), 403, "E0403");
            if (role.equals("DISPATCHER")) {
                assertResponse(manager.get("/adm/categories"), 403, "E0403");
                assertResponse(manager.get("/adm/parameters"), 403, "E0403");
            }
        }
    }

    @Test
    void minimalCreatesDefaultToEnabledAndPartialUpdatesPreserveOmittedFieldsAndClearNullableFields() throws Exception {
        Browser manager = login("DISPATCHER", false);
        JsonNode customer = createCustomer(manager, code(), "合成最小客戶");
        assertThat(customer.get("enabled").asBoolean()).isTrue();
        assertThat(customer.get("taxId").isNull()).isTrue();
        assertThat(customer.get("phone").isNull()).isTrue();
        assertThat(customer.get("email").isNull()).isTrue();
        JsonNode first = patch(manager, "/crm/customers/" + id(customer), object("version", 0, "phone", "合成分機-1", "taxId", "NOT-8-DIGITS"));
        assertThat(first.get("name").asString()).isEqualTo("合成最小客戶");
        assertThat(first.get("phone").asString()).isEqualTo("合成分機-1");
        JsonNode cleared = patch(manager, "/crm/customers/" + id(customer), object("version", first.get("version").asLong(),
                "phone", "\ufeff", "taxId", "\u00a0\u202f", "email", "   "));
        assertThat(cleared.get("name").asString()).isEqualTo("合成最小客戶");
        for (String field : List.of("taxId", "phone", "email")) assertThat(cleared.get(field).isNull()).isTrue();
        JsonNode contact = createContact(manager, customer, Map.of("name", "合成聯絡人"));
        assertThat(contact.get("enabled").asBoolean()).isTrue();
        assertThat(contact.get("title").isNull()).isTrue();
        JsonNode address = createAddress(manager, customer, Map.of("address", "合成測試地址"));
        assertThat(address.get("enabled").asBoolean()).isTrue();
        assertThat(address.get("label").isNull()).isTrue();
        assertThat(address.get("addressType").isNull()).isTrue();
        assertResponse(manager.patch("/crm/customers/" + id(customer), Map.of("version", cleared.get("version").asLong())), 400, "E0400");
    }

    @Test
    void customerCodeIsUniqueWhileNamesAndOptionalNonEightDigitTaxIdentifiersAreNotUnique() throws Exception {
        Browser manager = login("ADMIN", false);
        String customerCode = code();
        Map<String, Object> body = object("customerCode", customerCode, "name", "合成同名客戶", "taxId", "SYNTHETIC-NON8");
        assertResponse(manager.post("/crm/customers", body, true), 201, "0000");
        assertResponse(manager.post("/crm/customers", body, true), 409, "E0409");
        body.put("customerCode", code());
        assertResponse(manager.post("/crm/customers", body, true), 201, "0000");
        createCustomer(manager, "9", "合成單碼客戶");
        createCustomer(manager, "A".repeat(64), "合成最大代碼客戶");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.crm_customer WHERE name=? AND tax_id=?", Long.class,
                "合成同名客戶", "SYNTHETIC-NON8")).isEqualTo(2);
    }

    @Test
    void customerFieldsEnforceEngineeringBoundsUnicodeNormalizationAndBasicEmailShape() throws Exception {
        Browser manager = login("ADMIN", false);
        for (String name : List.of("x".repeat(201), "\u00a0\u202f", "\ufeff", "合成\u0000控制", "合成\n換行", "合成\u007f控制")) {
            assertResponse(manager.post("/crm/customers", customerBody(code(), name), true), 400, "E0400");
        }
        for (String code : List.of("A".repeat(65), "INVALID CODE", "_INVALID", "合成代碼")) {
            assertResponse(manager.post("/crm/customers", customerBody(code, "合成代碼驗證"), true), 400, "E0400");
        }
        for (Map<String, Object> invalid : List.of(object("phone", "1".repeat(51)), object("taxId", "T".repeat(51)),
                object("email", "not-an-email"), object("email", "a@bad domain.test"), object("email", "a@bad\u00a0domain.test"),
                object("email", "a@bad\ufeffdomain.test"))) {
            Map<String, Object> body = new LinkedHashMap<>(customerBody(code(), "合成欄位驗證"));
            body.putAll(invalid);
            assertResponse(manager.post("/crm/customers", body, true), 400, "E0400");
        }
        String longestEmail = "u".repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(56) + ".test";
        assertThat(longestEmail.length()).isEqualTo(254);
        Map<String, Object> maximal = object("customerCode", code(), "name", "😀".repeat(200), "phone", "1".repeat(50),
                "taxId", "T".repeat(50), "email", longestEmail);
        assertResponse(manager.post("/crm/customers", maximal, true), 201, "0000");
        maximal.put("customerCode", code()); maximal.put("email", longestEmail + "x");
        assertResponse(manager.post("/crm/customers", maximal, true), 400, "E0400");
        JsonNode normalized = createCustomer(manager, code(), "\u00a0合成 e\u0301\u202f");
        assertThat(normalized.get("name").asString()).isEqualTo("合成 é");
    }

    @Test
    void multipleContactsAndAddressesBelongToTheirCustomerAndForeignKeysRejectOrphans() throws Exception {
        Browser manager = login("DISPATCHER", false);
        JsonNode customer = createCustomer(manager, code(), "合成多聯絡資料");
        for (int index = 0; index < 2; index++) {
            createContact(manager, customer, object("name", "合成同名聯絡人", "phone", "合成分機", "email", "contact@example.test"));
            createAddress(manager, customer, object("label", "合成同名地址", "address", "合成常用地址", "addressType", "自由文字測試類別"));
        }
        JsonNode contacts = page(manager.get("/crm/customers/" + id(customer) + "/contacts"));
        JsonNode addresses = page(manager.get("/crm/customers/" + id(customer) + "/addresses"));
        assertThat(contacts.size()).isEqualTo(2); assertThat(addresses.size()).isEqualTo(2);
        for (JsonNode child : contacts) assertThat(child.get("customerId").asString()).isEqualTo(id(customer));
        for (JsonNode child : addresses) assertThat(child.get("customerId").asString()).isEqualTo(id(customer));
        UUID actor = UUID.fromString(contacts.get(0).get("createdBy").asString());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO logistics.crm_contact(id,customer_id,name,enabled,version,created_at,updated_at,created_by,updated_by)
                VALUES (?,?,'合成孤立聯絡人',true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                """, UUID.randomUUID(), UUID.randomUUID(), actor, actor)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO logistics.crm_address(id,customer_id,address,enabled,version,created_at,updated_at,created_by,updated_by)
                VALUES (?,?,'合成孤立地址',true,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                """, UUID.randomUUID(), UUID.randomUUID(), actor, actor)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void childPathParentMismatchReturns404WithoutChangingEitherCustomersChildren() throws Exception {
        Browser manager = login("ADMIN", false);
        JsonNode owner = createCustomer(manager, code(), "合成正確父客戶");
        JsonNode other = createCustomer(manager, code(), "合成另一父客戶");
        JsonNode contact = createContact(manager, owner, Map.of("name", "合成不可移轉聯絡人"));
        JsonNode address = createAddress(manager, owner, Map.of("address", "合成不可移轉地址"));
        assertResponse(manager.patch("/crm/customers/" + id(other) + "/contacts/" + id(contact), Map.of("version", 0, "name", "不可越界")),
                404, "E0404");
        assertResponse(manager.patch("/crm/customers/" + id(other) + "/addresses/" + id(address), Map.of("version", 0, "address", "不可越界")),
                404, "E0404");
        assertThat(page(manager.get("/crm/customers/" + id(owner) + "/contacts")).get(0).get("name").asString())
                .isEqualTo("合成不可移轉聯絡人");
        assertThat(page(manager.get("/crm/customers/" + id(other) + "/contacts")).size()).isZero();
        assertThat(page(manager.get("/crm/customers/" + id(other) + "/addresses")).size()).isZero();
    }

    @Test
    void childFieldsValidateBoundsControlsAndFreeTextAddressTypeWithoutInventingAnEnum() throws Exception {
        Browser manager = login("ADMIN", false);
        JsonNode customer = createCustomer(manager, code(), "合成子項欄位驗證");
        for (Map<String, Object> invalid : List.of(object("name", "x".repeat(201)), object("name", "\u00a0\u202f"), object("name", "\ufeff"),
                object("name", "合成\u0001控制"), object("name", "合成", "title", "T".repeat(101)),
                object("name", "合成", "phone", "1".repeat(51)), object("name", "合成", "email", "bad-email"))) {
            assertResponse(manager.post("/crm/customers/" + id(customer) + "/contacts", invalid, true), 400, "E0400");
        }
        createContact(manager, customer, object("name", "😀".repeat(200), "title", "T".repeat(100), "phone", "1".repeat(50)));
        for (Map<String, Object> invalid : List.of(object("address", "x".repeat(1001)), object("address", "\u00a0\u202f"), object("address", "\ufeff"),
                object("address", "合成\t控制"), object("address", "合成", "label", "L".repeat(101)),
                object("address", "合成", "addressType", "T".repeat(41)))) {
            assertResponse(manager.post("/crm/customers/" + id(customer) + "/addresses", invalid, true), 400, "E0400");
        }
        JsonNode address = createAddress(manager, customer, object("label", "L".repeat(100),
                "address", "a".repeat(499) + "\n" + "b".repeat(500), "addressType", "T".repeat(40)));
        assertThat(address.get("address").asString()).contains("\n").hasSize(1000);
        JsonNode cleared = patch(manager, "/crm/customers/" + id(customer) + "/addresses/" + id(address),
                object("version", address.get("version").asLong(), "addressType", "\u00a0\u202f", "label", null));
        assertThat(cleared.get("addressType").isNull()).isTrue(); assertThat(cleared.get("label").isNull()).isTrue();
    }

    @Test
    void identifiersCodesParentAndActorFieldsAreImmutableAndThereAreNoDeleteEndpoints() throws Exception {
        Browser manager = login("ADMIN", false);
        JsonNode customer = createCustomer(manager, code(), "合成不可變欄位");
        JsonNode contact = createContact(manager, customer, Map.of("name", "合成聯絡人"));
        JsonNode address = createAddress(manager, customer, Map.of("address", "合成地址"));
        for (String field : List.of("id", "customerCode", "createdBy", "updatedBy", "createdAt", "updatedAt", "actor", "unknown")) {
            assertResponse(manager.patch("/crm/customers/" + id(customer), object("version", 0, field, "forbidden")), 400, "E0400");
        }
        for (String field : List.of("id", "customerId", "createdBy", "updatedBy", "actor", "unknown")) {
            for (String path : List.of("/crm/customers/" + id(customer) + "/contacts/" + id(contact),
                    "/crm/customers/" + id(customer) + "/addresses/" + id(address))) {
                assertResponse(manager.patch(path, object("version", 0, field, "forbidden")), 400, "E0400");
            }
        }
        Map<String, Object> forgedCreate = new LinkedHashMap<>(customerBody(code(), "合成偽造操作者"));
        forgedCreate.put("createdBy", UUID.randomUUID().toString());
        assertResponse(manager.post("/crm/customers", forgedCreate, true), 400, "E0400");
        for (String path : List.of("/crm/customers/" + id(customer), "/crm/customers/" + id(customer) + "/contacts/" + id(contact),
                "/crm/customers/" + id(customer) + "/addresses/" + id(address))) {
            assertThat(manager.send("DELETE", path, null, manager.csrf()).statusCode()).isEqualTo(405);
        }
        assertThat(count("SELECT count(*) FROM logistics.crm_customer")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM logistics.crm_contact")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM logistics.crm_address")).isEqualTo(1);
    }

    @Test
    void auditFieldsComeFromTheServerAndInvalidPartialUpdatesRollBackEveryField() throws Exception {
        Account creator = account("ADMIN", false);
        Browser admin = new Browser(); assertResponse(admin.login(creator), 200, "0000");
        Account updater = account("DISPATCHER", false);
        Browser dispatcher = new Browser(); assertResponse(dispatcher.login(updater), 200, "0000");
        JsonNode customer = createCustomer(admin, code(), "合成原始名稱");
        JsonNode before = data(admin.get("/crm/customers/" + id(customer)));
        assertResponse(dispatcher.patch("/crm/customers/" + id(customer), object("version", 0, "name", "不得部分保存", "email", "invalid")),
                400, "E0400");
        JsonNode unchanged = data(admin.get("/crm/customers/" + id(customer)));
        assertThat(unchanged).isEqualTo(before);
        JsonNode updated = patch(dispatcher, "/crm/customers/" + id(customer), Map.of("version", 0, "name", "合成新名稱"));
        assertThat(updated.get("createdBy").asString()).isEqualTo(creator.id().toString());
        assertThat(updated.get("updatedBy").asString()).isEqualTo(updater.id().toString());
        assertThat(updated.get("createdAt").asString()).isEqualTo(before.get("createdAt").asString());
        assertThat(Instant.parse(updated.get("updatedAt").asString())).isAfterOrEqualTo(Instant.parse(before.get("updatedAt").asString()));
        assertThat(updated.get("version").asLong()).isEqualTo(1);
        JsonNode contact = createContact(admin, customer, Map.of("name", "合成審計聯絡人"));
        JsonNode address = createAddress(admin, customer, Map.of("address", "合成審計地址"));
        JsonNode updatedContact = patch(dispatcher, "/crm/customers/" + id(customer) + "/contacts/" + id(contact),
                Map.of("version", 0, "name", "合成聯絡人新名稱"));
        JsonNode updatedAddress = patch(dispatcher, "/crm/customers/" + id(customer) + "/addresses/" + id(address),
                Map.of("version", 0, "label", "合成新地址標籤"));
        for (JsonNode child : List.of(updatedContact, updatedAddress)) {
            assertThat(child.get("createdBy").asString()).isEqualTo(creator.id().toString());
            assertThat(child.get("updatedBy").asString()).isEqualTo(updater.id().toString());
            assertThat(child.get("version").asLong()).isEqualTo(1);
        }
    }

    @Test
    void disablingRetainsAllHistoryAndCustomerOptionsCannotBeOverriddenByFilters() throws Exception {
        Browser manager = login("DISPATCHER", false);
        JsonNode active = createCustomer(manager, code(), "合成有效搜尋");
        JsonNode disabled = createCustomer(manager, code(), "合成停用獨有搜尋");
        JsonNode contact = createContact(manager, disabled, Map.of("name", "合成歷史聯絡人"));
        JsonNode address = createAddress(manager, disabled, Map.of("address", "合成歷史地址"));
        patch(manager, "/crm/customers/" + id(disabled), Map.of("version", 0, "enabled", false));
        assertThat(data(manager.get("/crm/customers/" + id(disabled))).get("enabled").asBoolean()).isFalse();
        assertThat(page(manager.get("/crm/customers/" + id(disabled) + "/contacts")).get(0).get("id").asString()).isEqualTo(id(contact));
        assertThat(page(manager.get("/crm/customers/" + id(disabled) + "/addresses")).get(0).get("id").asString()).isEqualTo(id(address));
        assertThat(ids(page(manager.get("/crm/customer-options")))).containsExactly(id(active));
        assertThat(page(manager.get("/crm/customer-options?q=" + encode("停用獨有"))).size()).isZero();
        assertResponse(manager.get("/crm/customer-options?enabled=false"), 400, "E0400");
        assertResponse(manager.get("/crm/customer-options?enabled=true"), 400, "E0400");
        patch(manager, "/crm/customers/" + id(disabled) + "/contacts/" + id(contact), Map.of("version", 0, "enabled", false));
        patch(manager, "/crm/customers/" + id(disabled) + "/addresses/" + id(address), Map.of("version", 0, "enabled", false));
        assertThat(page(manager.get("/crm/customers/" + id(disabled) + "/contacts?enabled=true")).size()).isZero();
        assertThat(page(manager.get("/crm/customers/" + id(disabled) + "/addresses?enabled=false")).size()).isEqualTo(1);
        assertThat(queries.findCurrent(UUID.fromString(id(disabled)))).isPresent();
    }

    @Test
    void pagingSearchingEnabledFiltersAndStableTiesUseOnlyAllowedSortFields() throws Exception {
        Browser manager = login("ADMIN", false);
        createCustomer(manager, "SYNTHETIC_B", "合成相同名稱");
        JsonNode a = createCustomer(manager, "SYNTHETIC_A", "合成相同名稱");
        JsonNode c = createCustomer(manager, "SYNTHETIC_C", "合成相同名稱");
        patch(manager, "/crm/customers/" + id(c), Map.of("version", 0, "enabled", false));
        JsonNode first = data(manager.get("/crm/customers?sort=name,asc&size=2"));
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        List<String> stableCustomers = jdbc.queryForList("SELECT customer_code FROM logistics.crm_customer ORDER BY name,id", String.class);
        assertThat(strings(first.get("content"), "customerCode")).containsExactlyElementsOf(stableCustomers.subList(0, 2));
        assertThat(strings(page(manager.get("/crm/customers?sort=name,asc&size=2&page=1")), "customerCode"))
                .containsExactlyElementsOf(stableCustomers.subList(2, 3));
        assertThat(page(manager.get("/crm/customers?q=SYNTHETIC_A")).size()).isEqualTo(1);
        assertThat(page(manager.get("/crm/customers?enabled=false")).size()).isEqualTo(1);
        assertThat(page(manager.get("/crm/customers?enabled=true")).size()).isEqualTo(2);
        for (String query : List.of("page=-1", "size=0", "size=101", "page=2147483648", "sort=passwordHash,asc", "sort=name,sideways",
                "enabled=other", "unknown=1", "size=2&size=3")) {
            assertResponse(manager.get("/crm/customers?" + query), 400, "E0400");
        }
        createContact(manager, a, Map.of("name", "合成同名聯絡人")); createContact(manager, a, Map.of("name", "合成同名聯絡人"));
        List<String> stableContacts = jdbc.queryForList("SELECT id::text FROM logistics.crm_contact ORDER BY name,id", String.class);
        assertThat(ids(page(manager.get("/crm/customers/" + id(a) + "/contacts?sort=name,asc")))).containsExactlyElementsOf(stableContacts);
        createCustomer(manager, code(), "合成 literal % 名稱"); createCustomer(manager, code(), "合成 literal X 名稱");
        assertThat(page(manager.get("/crm/customers?q=" + encode("%"))).size()).isEqualTo(1);
    }

    @Test
    void everyWriteRejectsUnknownQueryArgumentsAndPatchRequiresARealMutableField() throws Exception {
        Browser manager = login("ADMIN", false);
        JsonNode customer = createCustomer(manager, code(), "合成查詢欄位守衛");
        JsonNode contact = createContact(manager, customer, Map.of("name", "合成聯絡人"));
        JsonNode address = createAddress(manager, customer, Map.of("address", "合成地址"));
        assertResponse(manager.post("/crm/customers?unknown=1", customerBody(code(), "不得建立"), true), 400, "E0400");
        for (String path : List.of("/crm/customers/" + id(customer), "/crm/customers/" + id(customer) + "/contacts/" + id(contact),
                "/crm/customers/" + id(customer) + "/addresses/" + id(address))) {
            assertResponse(manager.patch(path, Map.of("version", 0)), 400, "E0400");
            assertResponse(manager.patch(path + "?unknown=1", Map.of("version", 0, "enabled", false)), 400, "E0400");
        }
        assertResponse(manager.post("/crm/customers/" + id(customer) + "/contacts?unknown=1", Map.of("name", "不得建立"), true), 400, "E0400");
        assertResponse(manager.post("/crm/customers/" + id(customer) + "/addresses?unknown=1", Map.of("address", "不得建立"), true), 400, "E0400");
        assertThat(count("SELECT count(*) FROM logistics.crm_customer")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM logistics.crm_contact")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM logistics.crm_address")).isEqualTo(1);
    }

    @Test
    void simultaneousCustomerAndChildVersionsAllowExactlyOneUpdateEach() throws Exception {
        Browser manager = login("ADMIN", false);
        JsonNode customer = createCustomer(manager, code(), "合成版本競爭");
        JsonNode contact = createContact(manager, customer, Map.of("name", "合成聯絡版本"));
        JsonNode address = createAddress(manager, customer, Map.of("address", "合成地址版本"));
        String token = manager.csrf();
        for (String path : List.of("/crm/customers/" + id(customer), "/crm/customers/" + id(customer) + "/contacts/" + id(contact),
                "/crm/customers/" + id(customer) + "/addresses/" + id(address))) {
            try (var pool = Executors.newFixedThreadPool(2)) {
                var replies = pool.invokeAll(List.<Callable<Integer>>of(
                        () -> manager.send("PATCH", path, Map.of("version", 0, "enabled", false), token).statusCode(),
                        () -> manager.send("PATCH", path, Map.of("version", 0, "enabled", false), token).statusCode()));
                assertThat(List.of(replies.get(0).get(), replies.get(1).get())).containsExactlyInAnyOrder(200, 409);
            }
        }
    }

    @Test
    void revokedActorsCannotSubmitAWriteThatWasWaitingForTheCanonicalAuthorizationLock() throws Exception {
        for (String role : List.of("ADMIN", "DISPATCHER")) {
            Account actor = account(role, false);
            Browser manager = new Browser(); assertResponse(manager.login(actor), 200, "0000");
            String token = manager.csrf(); String rejectedCode = code();
            try (var pool = Executors.newSingleThreadExecutor(); var lock = dataSource.getConnection()) {
                lock.setAutoCommit(false);
                try (var statement = lock.createStatement()) { statement.executeQuery("SELECT id FROM logistics.upms_lock WHERE id=1 FOR UPDATE").close(); }
                var pending = pool.submit(() -> manager.send("POST", "/crm/customers", customerBody(rejectedCode, "不得保存撤銷身分"), token));
                awaitDatabaseLock("upms_lock");
                try (var statement = lock.prepareStatement("UPDATE logistics.upms_account SET auth_version=auth_version+1 WHERE id=?")) {
                    statement.setObject(1, actor.id()); statement.executeUpdate();
                }
                lock.commit();
                assertResponse(pending.get(15, TimeUnit.SECONDS), 401, "E0401");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM logistics.crm_customer WHERE customer_code=?", Long.class, rejectedCode)).isZero();
        }
    }

    @Test
    void failedChildPersistenceRollsBackWithoutChangingItsCustomerAuditOrVersion() throws Exception {
        Browser manager = login("ADMIN", false);
        JsonNode customer = createCustomer(manager, code(), "合成回滾父客戶");
        JsonNode before = data(manager.get("/crm/customers/" + id(customer)));
        jdbc.execute("""
                CREATE FUNCTION logistics.synthetic_reject_crm_contact() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'synthetic contact failure'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER synthetic_reject_crm_contact BEFORE INSERT ON logistics.crm_contact "
                + "FOR EACH ROW EXECUTE FUNCTION logistics.synthetic_reject_crm_contact()");
        try {
            HttpResponse<String> response = manager.post("/crm/customers/" + id(customer) + "/contacts", Map.of("name", "合成不得殘留"), true);
            assertThat(response.statusCode()).isBetween(500, 599);
            assertThat(response.body()).doesNotContain("synthetic contact failure", "SQLException", "INSERT INTO");
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_reject_crm_contact ON logistics.crm_contact");
            jdbc.execute("DROP FUNCTION logistics.synthetic_reject_crm_contact()");
        }
        assertThat(count("SELECT count(*) FROM logistics.crm_contact")).isZero();
        assertThat(data(manager.get("/crm/customers/" + id(customer)))).isEqualTo(before);
    }

    @Test
    void referenceSourceRequiresTheCallersWritableTransactionAndRejectsDisabledOrMissingCustomers() throws Exception {
        Browser manager = login("ADMIN", false);
        JsonNode customer = createCustomer(manager, code(), "合成引用來源"); UUID customerId = UUID.fromString(id(customer));
        assertThatThrownBy(() -> queries.requireEnabledForNewReference(customerId)).isInstanceOf(IllegalTransactionStateException.class);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager); readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(tx -> queries.requireEnabledForNewReference(customerId)))
                .isInstanceOf(IllegalTransactionStateException.class);
        CrmQueries.Snapshot source = new TransactionTemplate(transactionManager).execute(tx -> queries.requireEnabledForNewReference(customerId));
        assertThat(source).isNotNull(); assertThat(source.id()).isEqualTo(customerId);
        assertThat(source.code()).isEqualTo(customer.get("customerCode").asString()); assertThat(source.getClass().isRecord()).isTrue();
        patch(manager, "/crm/customers/" + customerId, Map.of("version", 0, "enabled", false));
        assertThat(queries.findCurrent(customerId)).isPresent();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> queries.requireEnabledForNewReference(customerId)))
                .isInstanceOfSatisfying(UpmsException.class, failure -> assertThat(failure.status()).isEqualTo(400));
        assertThat(queries.findCurrent(UUID.randomUUID())).isEmpty();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> queries.requireEnabledForNewReference(UUID.randomUUID())))
                .isInstanceOfSatisfying(UpmsException.class, failure -> assertThat(failure.status()).isEqualTo(404));
    }

    @Test
    void referenceSourceLockMakesRenameAndDisableWaitAndReturnedCopyDoesNotChange() throws Exception {
        Browser manager = login("DISPATCHER", false);
        JsonNode customer = createCustomer(manager, code(), "合成鎖定前名稱"); UUID customerId = UUID.fromString(id(customer));
        String token = manager.csrf(); AtomicReference<Future<HttpResponse<String>>> pending = new AtomicReference<>();
        CrmQueries.Snapshot source;
        try (var pool = Executors.newSingleThreadExecutor()) {
            source = new TransactionTemplate(transactionManager).execute(transaction -> {
                CrmQueries.Snapshot held = queries.requireEnabledForNewReference(customerId);
                pending.set(pool.submit(() -> manager.send("PATCH", "/crm/customers/" + customerId,
                        Map.of("version", 0, "name", "合成改名並停用", "enabled", false), token)));
                awaitDatabaseLock("crm_customer");
                assertThat(pending.get().isDone()).isFalse();
                return held;
            });
            assertResponse(pending.get().get(15, TimeUnit.SECONDS), 200, "0000");
        }
        assertThat(source).isNotNull(); assertThat(source.name()).isEqualTo("合成鎖定前名稱"); assertThat(source.version()).isZero();
        CrmQueries.Snapshot current = queries.findCurrent(customerId).orElseThrow();
        assertThat(current.name()).isEqualTo("合成改名並停用"); assertThat(current.version()).isEqualTo(1);
        assertThat(source.name()).isEqualTo("合成鎖定前名稱");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> queries.requireEnabledForNewReference(customerId)))
                .isInstanceOfSatisfying(UpmsException.class, failure -> assertThat(failure.status()).isEqualTo(400));
    }

    @Test
    void referenceLockRefreshesPreviouslyManagedCustomerStateAfterAnExternalCommittedChange() throws Exception {
        Browser manager = login("ADMIN", false);
        for (boolean disabled : List.of(false, true)) {
            JsonNode customer = createCustomer(manager, code(), "合成快取前名稱"); UUID customerId = UUID.fromString(id(customer));
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertThat(queries.findCurrent(customerId).orElseThrow().name()).isEqualTo("合成快取前名稱");
                try (var connection = dataSource.getConnection(); var update = connection.prepareStatement(
                        "UPDATE logistics.crm_customer SET name='合成外部已提交名稱',enabled=?,version=version+1 WHERE id=?")) {
                    assertThat(connection.getAutoCommit()).isTrue(); update.setBoolean(1, !disabled); update.setObject(2, customerId); update.executeUpdate();
                } catch (Exception failure) { throw new IllegalStateException("Could not arrange the external committed customer update", failure); }
                if (disabled) {
                    assertThatThrownBy(() -> queries.requireEnabledForNewReference(customerId)).isInstanceOfSatisfying(UpmsException.class,
                            failure -> assertThat(failure.status()).isEqualTo(400));
                    tx.setRollbackOnly();
                } else {
                    CrmQueries.Snapshot refreshed = queries.requireEnabledForNewReference(customerId);
                    assertThat(refreshed.name()).isEqualTo("合成外部已提交名稱"); assertThat(refreshed.version()).isEqualTo(1);
                }
            });
        }
    }

    @Test
    void publicAuthorizationWriteRefreshesAnActorAlreadyManagedBeforeExternalRevocation() throws Exception {
        Account actor = account("DISPATCHER", false);
        Instant now = Instant.now();
        SessionPrincipal principal = new SessionPrincipal(actor.id(), 0, now, now);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(authorization.requireCustomerManager(principal).id()).isEqualTo(actor.id());
            try (var connection = dataSource.getConnection(); var update = connection.prepareStatement(
                    "UPDATE logistics.upms_account SET auth_version=auth_version+1 WHERE id=?")) {
                assertThat(connection.getAutoCommit()).isTrue(); update.setObject(1, actor.id()); update.executeUpdate();
            } catch (Exception failure) { throw new IllegalStateException("Could not arrange external actor revocation", failure); }
            assertThatThrownBy(() -> authorization.requireCustomerManagerForWrite(principal))
                    .isInstanceOfSatisfying(UpmsException.class, failure -> assertThat(failure.status()).isEqualTo(401));
            tx.setRollbackOnly();
        });
    }

    @Test
    void crmUsesOnlyPublicOtherModuleBoundariesAndKeepsOrderPersistenceOutsideCrm() throws Exception {
        try (var files = Files.walk(Path.of("target/classes/com/xksystem/logistics/crm"))) {
            var compiled = files.filter(path -> path.toString().endsWith(".class")).toList(); assertThat(compiled).isNotEmpty();
            for (Path file : compiled) {
                String constants = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertThat(constants.contains("com/xksystem/logistics/upms/internal/"))
                        .as("CRM depends on the public UPMS customer-manager boundary: " + file.getFileName()).isFalse();
                assertThat(constants.contains("com/xksystem/logistics/adm/internal/"))
                        .as("CRM does not reach ADM repositories: " + file.getFileName()).isFalse();
                assertThat(constants.contains("com/xksystem/logistics/tom/internal/"))
                        .as("CRM does not reach TOM repositories: " + file.getFileName()).isFalse();
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='logistics' "
                + "AND table_name IN ('crm_customer','crm_contact','crm_address') AND column_name IN ('order_id','order_no','snapshot')", Long.class))
                .isZero();
    }

    private void awaitDatabaseLock(String table) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // A monitor sharing the caller transaction can retain PostgreSQL's first statistics snapshot.
        // Use a separate autocommit connection so every observation sees current lock waiters.
        try (var observer = dataSource.getConnection(); var statement = observer.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE ?")) {
            assertThat(observer.getAutoCommit()).as("The lock observer does not share the caller transaction").isTrue();
            statement.setString(1, "%" + table + "%");
            while (System.nanoTime() < deadline) {
                try (var result = statement.executeQuery()) { result.next(); if (result.getLong(1) > 0) return; }
                try { Thread.sleep(20); } catch (InterruptedException interruption) { Thread.currentThread().interrupt(); throw new IllegalStateException(interruption); }
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Could not observe the isolated database lock", failure);
        }
        throw new AssertionError("The expected database row lock was not observed for " + table);
    }
    private String code() { return "CRM_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT); }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String id(JsonNode value) { return value.get("id").asString(); }
    private Map<String, Object> customerBody(String code, String name) { return Map.of("customerCode", code, "name", name); }
    private Map<String, Object> object(Object... fields) { Map<String, Object> value = new LinkedHashMap<>(); for (int i = 0; i < fields.length; i += 2) value.put((String) fields[i], fields[i + 1]); return value; }
    private JsonNode createCustomer(Browser browser, String code, String name) throws Exception {
        HttpResponse<String> response = browser.post("/crm/customers", customerBody(code, name), true); assertResponse(response, 201, "0000"); return data(response);
    }
    private JsonNode createContact(Browser browser, JsonNode customer, Object body) throws Exception {
        HttpResponse<String> response = browser.post("/crm/customers/" + id(customer) + "/contacts", body, true); assertResponse(response, 201, "0000"); return data(response);
    }
    private JsonNode createAddress(Browser browser, JsonNode customer, Object body) throws Exception {
        HttpResponse<String> response = browser.post("/crm/customers/" + id(customer) + "/addresses", body, true); assertResponse(response, 201, "0000"); return data(response);
    }
    private JsonNode patch(Browser browser, String path, Object body) throws Exception { HttpResponse<String> response = browser.patch(path, body); assertResponse(response, 200, "0000"); return data(response); }
    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).get("data"); }
    private JsonNode page(HttpResponse<String> response) throws Exception { assertResponse(response, 200, "0000"); return data(response).get("content"); }
    private List<String> ids(JsonNode values) { return strings(values, "id"); }
    private List<String> strings(JsonNode values, String field) { List<String> result = new ArrayList<>(); for (JsonNode value : values) result.add(value.get(field).asString()); return result; }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private void assertResponse(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("CRM HTTP status").isEqualTo(status);
        JsonNode body = json.readTree(response.body()); assertThat(body.get("code").asString()).isEqualTo(code); assertThat(body.get("timestamp").asString()).endsWith("+08:00");
    }
    private Account account(String role, boolean temporary) throws Exception {
        String username = "crm-test-" + UUID.randomUUID().toString().substring(0, 16); String password = UpmsTestData.password();
        try (var connection = dataSource.getConnection()) { return new Account(UpmsTestData.insert(connection, encoder, username, role, password, temporary, Instant.now()), username, password); }
    }
    private Browser login(String role, boolean temporary) throws Exception { Browser browser = new Browser(); assertResponse(browser.login(account(role, temporary)), 200, "0000"); return browser; }
    private record Account(UUID id, String username, String password) {}
    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).sslContext(TlsTestSupport.trustedContext()).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        String csrf() throws Exception { HttpResponse<String> response = get("/auth/csrf"); assertResponse(response, 200, "0000"); return data(response).get("token").asString(); }
        HttpResponse<String> login(Account account) throws Exception { return post("/auth/login", Map.of("username", account.username(), "password", account.password()), true); }
        HttpResponse<String> post(String path, Object body, boolean token) throws Exception { return send("POST", path, body, token ? csrf() : null); }
        HttpResponse<String> patch(String path, Object body) throws Exception { return send("PATCH", path, body, csrf()); }
        HttpResponse<String> send(String method, String path, Object body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
            if (token != null) request.header("X-CSRF-TOKEN", token); if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
