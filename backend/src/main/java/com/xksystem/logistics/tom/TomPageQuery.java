package com.xksystem.logistics.tom;

import com.xksystem.logistics.tom.domain.FieldIssue;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;

/** The original order page has no upper page bound; large pages can be empty without SQL OFFSET overflow. */
public record TomPageQuery(BigInteger page, int size, String sort, String type, UUID customerId) {
    private static final Set<String> FIELDS = Set.of("page", "size", "sort", "type", "customerId");
    private static final Set<String> SORTS = Set.of("updatedAt,desc", "updatedAt,asc", "orderNo,asc", "orderNo,desc");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public static TomPageQuery parse(MultiValueMap<String, String> query) {
        query.forEach((name, values) -> {
            if (!FIELDS.contains(name) || values == null || values.size() != 1)
                throw invalid("/query/" + escape(name), "request", "不接受未知或重複的查詢欄位");
        });
        BigInteger page = integer(query.getFirst("page"), BigInteger.ZERO, "page");
        if (page.signum() < 0) throw invalid("/query/page", "request", "頁碼不得為負數");
        BigInteger sizeValue = integer(query.getFirst("size"), BigInteger.valueOf(20), "size");
        if (sizeValue.signum() <= 0 || sizeValue.compareTo(BigInteger.valueOf(100)) > 0)
            throw invalid("/query/size", "request", "每頁筆數須為 1 至 100");
        String sort = query.containsKey("sort") ? query.getFirst("sort") : "updatedAt,desc";
        if (!SORTS.contains(sort)) throw invalid("/query/sort", "request", "請使用契約允許的排序");
        String type = query.getFirst("type");
        if (type != null && !Set.of("IMPORT", "EXPORT", "LOCAL").contains(type))
            throw invalid("/query/type", "OF03", "訂單類型須為 IMPORT、EXPORT 或 LOCAL");
        UUID customer = query.containsKey("customerId") ? uuid(query.getFirst("customerId"), "/query/customerId", "OF04") : null;
        return new TomPageQuery(page, sizeValue.intValueExact(), sort, type, customer);
    }

    public static UUID uuid(String value, String path, String field) {
        // UUID.fromString alone accepts short groups such as 1-1-1-1-1, outside format:uuid.
        if (value == null || !UUID_TEXT.matcher(value).matches()) throw invalid(path, field, "請提供完整有效 UUID");
        return UUID.fromString(value);
    }

    private static BigInteger integer(String value, BigInteger fallback, String name) {
        if (value == null) return fallback;
        if (!value.matches("[+-]?[0-9]+")) throw invalid("/query/" + name, "request", "請提供十進位整數查詢值");
        return new BigInteger(value);
    }

    static TomFailure invalid(String path, String field, String message) {
        return new TomFailure(400, "VALIDATION_ERROR", "請確認請求欄位與格式", null,
                List.of(new FieldIssue(path, field, "FORMAT", message)));
    }

    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
}
