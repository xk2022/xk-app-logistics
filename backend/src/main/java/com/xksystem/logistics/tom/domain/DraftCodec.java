package com.xksystem.logistics.tom.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Exact first-slice wire validation; free text is never trimmed, NFC-normalized or truncated. */
public final class DraftCodec {
    private DraftCodec() {}

    public static final long MAX_VERSION = 9_007_199_254_740_991L;
    private static final BigDecimal SAFE_MAX = BigDecimal.valueOf(MAX_VERSION);
    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
                    DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern DECIMAL = Pattern.compile("(0|[1-9][0-9]*)(\\.[0-9]+)?");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern DATETIME = Pattern.compile(
            "^([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([01][0-9]|2[0-3]):([0-5][0-9]):([0-5][0-9])(?:\\.([0-9]{1,6}))?([Zz]|([+-])([01][0-9]|2[0-3]):([0-5][0-9]))$");
    private static final Set<String> DRAFT_FIELDS = Set.of("type", "customerId", "customerReference", "remark", "shipping", "items", "tasks");
    private static final Set<String> SHIPPING_FIELDS = Set.of("shippingCompany", "vesselVoyage", "pol", "pod", "eta", "etd", "blNo");
    private static final Set<String> ITEM_FIELDS = Set.of("clientRef", "sequence", "itemKind", "containerType", "containerNo", "packageQty", "grossWeight", "cbm", "lastPickupDate", "deliveryCutoffAt");
    private static final Set<String> TASK_FIELDS = Set.of("clientRef", "itemRef", "taskKind", "pickupName", "pickupAddress", "deliveryName", "deliveryAddress", "siteContact", "plannedStartAt", "plannedEndAt", "predecessorRefs", "requiredTask");

    public record Replacement(long expectedVersion, ObjectNode draft) {}

    public static JsonNode parseJson(String raw) {
        if (raw == null) throw invalid("", "request", "請提供 JSON 物件");
        try {
            JsonNode value = JSON.readTree(raw);
            if (value == null) throw invalid("", "request", "請提供 JSON 物件");
            unicodeTree(value);
            return value;
        } catch (TomDomainException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Parser diagnostics can contain the request body: do not expose or log them.
            throw invalid("", "request", "JSON 格式不合法或有重複欄位");
        }
    }

    public static ObjectNode create(JsonNode raw) {
        ObjectNode draft = createShape(raw);
        validateGraph(draft);
        return draft;
    }

    /** Shape/format validation precedes idempotency lookup; cross-reference checks follow a new key. */
    public static ObjectNode createShape(JsonNode raw) {
        unicodeTree(raw);
        object(raw, "", DRAFT_FIELDS, Set.of("type", "customerId"));
        ObjectNode draft = NODES.objectNode();
        draft.put("type", enumText(raw.get("type"), "type", "/type", "OF03", Set.of("IMPORT", "EXPORT", "LOCAL"), false));
        JsonNode customer = raw.get("customerId");
        if (customer == null || !customer.isString() || !UUID_TEXT.matcher(customer.asString()).matches())
            throw invalid("/customerId", "OF04", "請提供有效客戶 UUID");
        draft.put("customerId", customer.asString());
        draft.set("customerReference", text(raw.get("customerReference"), "/customerReference", "OF06", 200, false));
        draft.set("remark", text(raw.get("remark"), "/remark", "OF07", 4000, true));
        draft.set("shipping", shipping(raw.get("shipping")));
        ArrayNode itemValues = array(raw.get("items"), "/items", "ORD-P02");
        ArrayNode taskValues = array(raw.get("tasks"), "/tasks", "ORD-P02");
        ArrayNode items = NODES.arrayNode();
        for (int i = 0; i < itemValues.size(); i++) items.add(item(itemValues.get(i), i));
        ArrayNode tasks = NODES.arrayNode();
        for (int i = 0; i < taskValues.size(); i++) tasks.add(task(taskValues.get(i), i));
        draft.set("items", items);
        draft.set("tasks", tasks);
        return draft;
    }

    public static Replacement replace(JsonNode raw) {
        Replacement replacement = replaceShape(raw);
        validateGraph(replacement.draft());
        return replacement;
    }

    public static Replacement replaceShape(JsonNode raw) {
        unicodeTree(raw);
        object(raw, "", Set.of("expectedVersion", "draft"), Set.of("expectedVersion", "draft"));
        long version = integer(raw.get("expectedVersion"), BigDecimal.ZERO, SAFE_MAX, "/expectedVersion", "AF01");
        return new Replacement(version, createShape(raw.get("draft")));
    }

    public static void validateGraph(ObjectNode draft) {
        List<FieldIssue> problems = graphIssues(draft);
        if (!problems.isEmpty()) throw new TomDomainException(400, "VALIDATION_ERROR", "草稿參照或依賴關係不合法", problems);
    }

    private static JsonNode shipping(JsonNode value) {
        if (nil(value)) return NODES.nullNode();
        object(value, "/shipping", SHIPPING_FIELDS, Set.of());
        ObjectNode result = NODES.objectNode();
        for (String field : List.of("shippingCompany", "vesselVoyage", "pol", "pod", "blNo"))
            result.set(field, text(value.get(field), "/shipping/" + field, shippingId(field), 200, false));
        result.set("eta", datetime(value.get("eta"), "/shipping/eta", "SF05"));
        result.set("etd", datetime(value.get("etd"), "/shipping/etd", "SF06"));
        return result;
    }

    private static ObjectNode item(JsonNode value, int index) {
        String path = "/items/" + index;
        object(value, path, ITEM_FIELDS, Set.of("clientRef"));
        ObjectNode result = NODES.objectNode();
        result.put("clientRef", ref(value.get("clientRef"), path + "/clientRef", "IF01"));
        result.set("sequence", positive(value.get("sequence"), path + "/sequence", "IF02"));
        String kind = enumText(value.get("itemKind"), "itemKind", path + "/itemKind", "IF03", Set.of("CONTAINER", "CARGO"), true);
        result.put("itemKind", kind);
        result.set("containerType", text(value.get("containerType"), path + "/containerType", "IF04", 200, false));
        result.set("containerNo", text(value.get("containerNo"), path + "/containerNo", "IF05", 200, false));
        result.set("packageQty", positive(value.get("packageQty"), path + "/packageQty", "IF06"));
        result.set("grossWeight", decimal(value.get("grossWeight"), path + "/grossWeight", "IF07"));
        result.set("cbm", decimal(value.get("cbm"), path + "/cbm", "IF08"));
        result.set("lastPickupDate", date(value.get("lastPickupDate"), path + "/lastPickupDate", "IF09"));
        result.set("deliveryCutoffAt", datetime(value.get("deliveryCutoffAt"), path + "/deliveryCutoffAt", "IF10"));
        return result;
    }

    private static ObjectNode task(JsonNode value, int index) {
        String path = "/tasks/" + index;
        object(value, path, TASK_FIELDS, Set.of("clientRef", "itemRef"));
        ObjectNode result = NODES.objectNode();
        result.put("clientRef", ref(value.get("clientRef"), path + "/clientRef", "TF01"));
        result.put("itemRef", ref(value.get("itemRef"), path + "/itemRef", "TF04"));
        for (String field : List.of("taskKind", "pickupName", "deliveryName"))
            result.set(field, text(value.get(field), path + "/" + field, taskId(field), 200, false));
        result.set("pickupAddress", text(value.get("pickupAddress"), path + "/pickupAddress", "TF07", 1000, false));
        result.set("deliveryAddress", text(value.get("deliveryAddress"), path + "/deliveryAddress", "TF09", 1000, false));
        JsonNode contact = value.get("siteContact");
        if (nil(contact)) result.putNull("siteContact");
        else {
            object(contact, path + "/siteContact", Set.of("name", "phone"), Set.of());
            ObjectNode normalized = NODES.objectNode();
            normalized.set("name", text(contact.get("name"), path + "/siteContact/name", "TF10", 200, false));
            normalized.set("phone", text(contact.get("phone"), path + "/siteContact/phone", "TF10", 50, false));
            result.set("siteContact", normalized);
        }
        JsonNode start = datetime(value.get("plannedStartAt"), path + "/plannedStartAt", "TF11");
        JsonNode end = datetime(value.get("plannedEndAt"), path + "/plannedEndAt", "TF12");
        result.set("plannedStartAt", start);
        result.set("plannedEndAt", end);
        ArrayNode predecessors = array(value.get("predecessorRefs"), path + "/predecessorRefs", "TF13");
        ArrayNode normalized = NODES.arrayNode();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < predecessors.size(); i++) {
            String predecessor = ref(predecessors.get(i), path + "/predecessorRefs/" + i, "TF13");
            if (!seen.add(predecessor)) throw invalid(path + "/predecessorRefs/" + i, "TF13", "前置參照不可重複");
            normalized.add(predecessor);
        }
        result.set("predecessorRefs", normalized);
        JsonNode required = value.get("requiredTask");
        if (nil(required)) result.putNull("requiredTask");
        else if (required.isBoolean() && required.asBoolean()) result.put("requiredTask", true);
        else throw invalid(path + "/requiredTask", "TF14", "本片只允許 true 或 null，尚未開放免除必要任務");
        return result;
    }

    /** Graph checks are iterative, so a long valid dependency chain does not exhaust the call stack. */
    static List<FieldIssue> graphIssues(JsonNode draft) {
        List<FieldIssue> problems = new ArrayList<>();
        JsonNode items = draft.path("items");
        JsonNode tasks = draft.path("tasks");
        Set<String> itemRefs = new HashSet<>();
        Set<String> sequence = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!itemRefs.add(item.path("clientRef").asString(""))) issue(problems, "/items/" + i + "/clientRef", "IF01", "項目 clientRef 不可重複");
            if (!item.path("sequence").isNull() && !item.path("sequence").isMissingNode()
                    && !sequence.add(item.get("sequence").decimalValue().stripTrailingZeros().toPlainString()))
                issue(problems, "/items/" + i + "/sequence", "IF02", "同單 sequence 不可重複");
        }
        Map<String, Integer> taskIndexes = new LinkedHashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            JsonNode task = tasks.get(i);
            String ref = task.path("clientRef").asString("");
            if (taskIndexes.putIfAbsent(ref, i) != null) issue(problems, "/tasks/" + i + "/clientRef", "TF01", "任務 clientRef 不可重複");
            if (!itemRefs.contains(task.path("itemRef").asString(""))) issue(problems, "/tasks/" + i + "/itemRef", "TF04", "itemRef 須存在於本次完整草稿");
            JsonNode start = task.path("plannedStartAt"), end = task.path("plannedEndAt");
            if (start.isString() && end.isString() && parseInstant(end.asString()).isBefore(parseInstant(start.asString())))
                issue(problems, "/tasks/" + i + "/plannedEndAt", "TF12", "預定結束不得早於預定開始");
        }
        int[] indegree = new int[tasks.size()];
        List<List<Integer>> next = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) next.add(new ArrayList<>());
        for (int i = 0; i < tasks.size(); i++) {
            JsonNode refs = tasks.get(i).path("predecessorRefs");
            Set<String> seen = new HashSet<>();
            for (int j = 0; j < refs.size(); j++) {
                String ref = refs.get(j).asString("");
                Integer predecessor = taskIndexes.get(ref);
                String path = "/tasks/" + i + "/predecessorRefs/" + j;
                if (!seen.add(ref)) issue(problems, path, "TF13", "前置參照不可重複");
                else if (predecessor == null) issue(problems, path, "TF13", "前置任務須存在於本次完整草稿");
                else if (predecessor == i) issue(problems, path, "TF13", "任務不可依賴自己");
                else { indegree[i]++; next.get(predecessor).add(i); }
            }
        }
        ArrayDeque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < indegree.length; i++) if (indegree[i] == 0) ready.add(i);
        while (!ready.isEmpty()) for (int following : next.get(ready.removeFirst())) if (--indegree[following] == 0) ready.add(following);
        for (int i = 0; i < indegree.length; i++) if (indegree[i] > 0) {
            issue(problems, "/tasks/" + i + "/predecessorRefs", "TF13", "前置任務不可形成循環");
            break;
        }
        return problems;
    }

    private static void issue(List<FieldIssue> target, String path, String field, String message) {
        target.add(new FieldIssue(path, field, "ORD-P02", message));
    }

    public static Instant parseInstant(String value) {
        if (value == null) throw invalid("", "request", "日期時間須含 offset 且最多六位小數秒");
        var match = DATETIME.matcher(value);
        if (!match.matches()) throw invalid("", "request", "日期時間須含 offset 且最多六位小數秒");
        try {
            int year = Integer.parseInt(match.group(1));
            if (year == 0) throw new DateTimeException("Invalid Gregorian year");
            String fraction = match.group(7);
            int nano = fraction == null ? 0 : Integer.parseInt(fraction + "0".repeat(9 - fraction.length()));
            LocalDateTime local = LocalDateTime.of(year, Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)),
                    Integer.parseInt(match.group(4)), Integer.parseInt(match.group(5)), Integer.parseInt(match.group(6)), nano);
            int offset = 0;
            if (match.group(9) != null) offset = (Integer.parseInt(match.group(10)) * 60 + Integer.parseInt(match.group(11))) * 60 * (match.group(9).equals("-") ? -1 : 1);
            return local.toInstant(ZoneOffset.UTC).minusSeconds(offset);
        } catch (DateTimeException | NumberFormatException failure) {
            throw invalid("", "request", "日期時間日曆值不合法");
        }
    }

    private static JsonNode datetime(JsonNode value, String path, String field) {
        if (nil(value)) return NODES.nullNode();
        if (!value.isString()) throw invalid(path, field, "請提供日期時間字串或 null");
        try { parseInstant(value.asString()); }
        catch (TomDomainException failure) { throw invalid(path, field, "日期時間須為有效含 offset 的 RFC3339，最多六位小數秒"); }
        return NODES.stringNode(value.asString());
    }

    private static JsonNode date(JsonNode value, String path, String field) {
        if (nil(value)) return NODES.nullNode();
        if (!value.isString() || !DATE.matcher(value.asString()).matches()) throw invalid(path, field, "請提供 YYYY-MM-DD 日期或 null");
        try { if (LocalDate.parse(value.asString()).getYear() == 0) throw new DateTimeException("Invalid year"); }
        catch (DateTimeException failure) { throw invalid(path, field, "日期日曆值不合法"); }
        return NODES.stringNode(value.asString());
    }

    private static JsonNode positive(JsonNode value, String path, String field) {
        return nil(value) ? NODES.nullNode() : NODES.numberNode(integer(value, BigDecimal.ONE, INT_MAX, path, field));
    }

    private static long integer(JsonNode value, BigDecimal min, BigDecimal max, String path, String field) {
        if (value == null || !value.isNumber()) throw invalid(path, field, "請提供範圍內的整數");
        try {
            BigDecimal numeric = value.decimalValue();
            // Compare before integer conversion: 1e10000000 must not allocate an enormous BigInteger.
            if (numeric.compareTo(min) < 0 || numeric.compareTo(max) > 0
                    || numeric.signum() != 0 && numeric.stripTrailingZeros().scale() > 0)
                throw invalid(path, field, "整數超出允許範圍或包含小數");
            return numeric.signum() == 0 ? 0 : numeric.longValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw invalid(path, field, "請提供範圍內的整數");
        }
    }

    private static JsonNode decimal(JsonNode value, String path, String field) {
        if (nil(value)) return NODES.nullNode();
        if (!value.isString() || value.asString().length() > 32 || !DECIMAL.matcher(value.asString()).matches())
            throw invalid(path, field, "請提供最多 32 字元的非負十進位字串，不使用指數或前置零");
        return NODES.stringNode(new BigDecimal(value.asString()).stripTrailingZeros().toPlainString());
    }

    private static JsonNode text(JsonNode value, String path, String field, int max, boolean allowBlank) {
        if (nil(value)) return NODES.nullNode();
        if (!value.isString()) throw invalid(path, field, "請提供文字或 null");
        String string = value.asString();
        unicode(string);
        if (string.codePointCount(0, string.length()) > max || !allowBlank && !hasNonWhitespace(string))
            throw invalid(path, field, "文字長度超界或只有空白；未知值請使用 null");
        return NODES.stringNode(string);
    }

    static boolean hasNonWhitespace(String value) {
        return value.codePoints().anyMatch(ch -> !(ch >= 0x09 && ch <= 0x0d || ch == 0x20 || ch == 0xa0
                || ch == 0x1680 || ch >= 0x2000 && ch <= 0x200a || ch == 0x2028 || ch == 0x2029
                || ch == 0x202f || ch == 0x205f || ch == 0x3000 || ch == 0xfeff));
    }

    private static String ref(JsonNode value, String path, String field) {
        if (value == null || !value.isString() || !REF.matcher(value.asString()).matches())
            throw invalid(path, field, "參照須為 1 至 64 個英數字、底線或連字號，並以英數字開頭");
        return value.asString();
    }

    private static String enumText(JsonNode value, String name, String path, String field, Set<String> allowed, boolean nullable) {
        if (nullable && nil(value)) return null;
        if (value == null || !value.isString() || !allowed.contains(value.asString())) throw invalid(path, field, name + " 須為契約允許值");
        return value.asString();
    }

    private static ArrayNode array(JsonNode value, String path, String field) {
        if (value == null) return NODES.arrayNode();
        if (!value.isArray()) throw invalid(path, field, "請提供陣列；省略代表空集合");
        return (ArrayNode) value;
    }

    private static void object(JsonNode value, String path, Set<String> allowed, Set<String> required) {
        if (value == null || !value.isObject()) throw invalid(path, "request", "請提供 JSON 物件");
        for (String field : value.propertyNames()) if (!allowed.contains(field))
            throw invalid(path + "/" + escape(field), "request", "不接受未知或伺服器管理的欄位");
        for (String field : required) if (!value.has(field) || value.get(field).isNull())
            throw invalid(path + "/" + escape(field), "request", "此欄位為必填");
    }

    private static boolean nil(JsonNode value) { return value == null || value.isNull(); }
    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private static TomDomainException invalid(String path, String field, String message) { return TomDomainException.invalid(path, field, message); }
    private static String shippingId(String field) {
        return switch (field) { case "shippingCompany" -> "SF01"; case "vesselVoyage" -> "SF02"; case "pol" -> "SF03"; case "pod" -> "SF04"; default -> "SF07"; };
    }
    private static String taskId(String field) { return switch (field) { case "taskKind" -> "TF05"; case "pickupName" -> "TF06"; default -> "TF08"; }; }

    /** Hash the parsed request, before default expansion or domain normalization. */
    public static String canonicalHash(JsonNode raw) {
        unicodeTree(raw);
        StringBuilder canonical = new StringBuilder();
        canonical(raw, canonical);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void canonical(JsonNode value, StringBuilder output) {
        if (value == null || value.isNull()) output.append("null");
        else if (value.isObject()) {
            output.append('{');
            List<String> fields = new ArrayList<>(value.propertyNames());
            fields.sort(DraftCodec::compareCodePoints);
            boolean first = true;
            for (String field : fields) {
                if (!first) output.append(','); first = false;
                quote(field, output); output.append(':'); canonical(value.get(field), output);
            }
            output.append('}');
        } else if (value.isArray()) {
            output.append('[');
            for (int i = 0; i < value.size(); i++) { if (i > 0) output.append(','); canonical(value.get(i), output); }
            output.append(']');
        } else if (value.isString()) quote(value.asString(), output);
        else if (value.isBoolean()) output.append(value.asBoolean() ? "true" : "false");
        else if (value.isNumber()) output.append(integer(value, BigDecimal.ZERO, SAFE_MAX, "", "request"));
        else throw invalid("", "request", "不支援的 JSON 值");
    }

    private static int compareCodePoints(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            int x = left.codePointAt(a), y = right.codePointAt(b);
            if (x != y) return Integer.compare(x, y);
            a += Character.charCount(x); b += Character.charCount(y);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }

    private static void quote(String value, StringBuilder output) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> output.append("\\\""); case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b"); case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n"); case '\r' -> output.append("\\r"); case '\t' -> output.append("\\t");
                default -> { if (ch < 0x20) output.append(String.format("\\u%04x", (int) ch)); else output.append(ch); }
            }
        }
        output.append('"');
    }

    private static void unicodeTree(JsonNode root) {
        if (root == null) throw invalid("", "request", "請提供 JSON 值");
        ArrayDeque<JsonNode> pending = new ArrayDeque<>(); pending.add(root);
        while (!pending.isEmpty()) {
            JsonNode value = pending.removeFirst();
            if (value.isString()) unicode(value.asString());
            else if (value.isObject()) for (String field : value.propertyNames()) { unicode(field); pending.add(value.get(field)); }
            else if (value.isArray()) for (JsonNode child : value) pending.add(child);
        }
    }

    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) throw invalid("", "request", "包含無效 Unicode 字元");
            } else if (Character.isLowSurrogate(ch)) throw invalid("", "request", "包含無效 Unicode 字元");
        }
    }
}
