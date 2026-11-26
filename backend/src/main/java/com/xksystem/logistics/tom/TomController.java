package com.xksystem.logistics.tom;

import com.xksystem.logistics.tom.internal.TomService;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TomController {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final TomService service;
    public TomController(TomService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TomEnvelope.Success> create(Authentication authentication, @RequestBody byte[] body,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        noQuery(query);
        return written(request, service.create(principal(authentication), utf8(body), key(request), TomEnvelope.trace(request)));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public TomEnvelope.Success list(Authentication authentication, @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request) {
        return TomEnvelope.ok(request, service.list(principal(authentication), TomPageQuery.parse(query)));
    }

    @GetMapping(value = "/{orderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TomEnvelope.Success get(Authentication authentication, @PathVariable String orderId,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        noQuery(query);
        return TomEnvelope.ok(request, service.get(principal(authentication), TomPageQuery.uuid(orderId, "/orderId", "OF01")));
    }

    @PutMapping(value = "/{orderId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TomEnvelope.Success> replace(Authentication authentication, @PathVariable String orderId,
            @RequestBody byte[] body, @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        noQuery(query);
        return written(request, service.replace(principal(authentication), TomPageQuery.uuid(orderId, "/orderId", "OF01"),
                utf8(body), key(request), TomEnvelope.trace(request)));
    }

    @GetMapping(value = "/{orderId}/acceptance-check", produces = MediaType.APPLICATION_JSON_VALUE)
    public TomEnvelope.Success check(Authentication authentication, @PathVariable String orderId,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        noQuery(query);
        return TomEnvelope.ok(request, service.check(principal(authentication), TomPageQuery.uuid(orderId, "/orderId", "OF01")));
    }

    @PostMapping(value = "/{orderId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TomEnvelope.Success> accept(Authentication authentication, @PathVariable String orderId,
            @RequestBody byte[] body, @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        noQuery(query);
        return written(request, service.accept(principal(authentication), TomPageQuery.uuid(orderId, "/orderId", "OF01"),
                utf8(body), key(request), TomEnvelope.trace(request)));
    }

    // Only POST gets this new protocol fallback. Legacy GET /{id}/accept remains an unknown-path 404.
    @PostMapping(value = "/{orderId}/accept")
    public void unsupportedAccept() {
        throw TomPageQuery.invalid("", "request", "請依受理契約提供 JSON 與有效媒體格式");
    }

    // Keep known-path protocol failures inside this controller's legacy-contract advice.
    @RequestMapping({"", "/{orderId}", "/{orderId}/acceptance-check"})
    public void unsupported() {
        throw TomPageQuery.invalid("", "request", "不支援此請求方法或媒體格式，請依原草稿契約操作");
    }

    @RequestMapping("/{*unmatched}")
    public void unknown() { throw new TomFailure(404, "ORDER_NOT_FOUND", "找不到授權範圍內的訂單"); }

    private static ResponseEntity<TomEnvelope.Success> written(HttpServletRequest request, TomService.WriteResult result) {
        var response = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()));
        if (result.location() != null) response.header(HttpHeaders.LOCATION, result.location());
        return response.body(TomEnvelope.ok(request, result.data()));
    }

    private static void noQuery(MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw TomPageQuery.invalid("/query", "request", "此操作不接受查詢參數");
    }

    private static String key(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1 || !KEY.matcher(values.getFirst()).matches())
            throw TomPageQuery.invalid("/headers/Idempotency-Key", "request", "請提供單一有效重送識別");
        return values.getFirst();
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw TomPageQuery.invalid("", "request", "請提供有效 UTF-8 JSON");
        }
    }

    private static SessionPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal))
            throw new TomFailure(401, "UNAUTHENTICATED", "請重新登入後操作");
        return principal;
    }
}
