package com.xksystem.logistics.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test-only assertions for the schema keywords used by the preserved OpenAPI 0.2.0. */
public final class TomContract {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode DOCUMENT = document();
    private static final JsonNode EXAMPLES = examples();

    private TomContract() {
    }

    public static JsonNode examples() {
        try { return JSON.readTree(Files.readString(Path.of("../docs/06_api/order-draft-contract-examples.json"))); }
        catch (Exception failure) { throw new IllegalStateException("Could not read the preserved TOM examples", failure); }
    }

    public static JsonNode example(String group, String id) {
        for (JsonNode entry : EXAMPLES.get(group)) if (entry.get("id").asString().equals(id)) return entry.deepCopy();
        throw new IllegalArgumentException("Unknown preserved TOM example: " + id);
    }

    public static List<JsonNode> group(String group) {
        List<JsonNode> entries = new ArrayList<>(); EXAMPLES.get(group).forEach(value -> entries.add(value.deepCopy())); return entries;
    }

    public static Set<String> fieldIds() {
        try (var lines = Files.lines(Path.of("../docs/05_database/first-slice-fields.csv"))) {
            Set<String> ids = new HashSet<>();
            lines.skip(1).filter(line -> !line.isBlank()).forEach(line -> ids.add(line.substring(1, line.indexOf('"', 1))));
            return Set.copyOf(ids);
        } catch (Exception failure) { throw new IllegalStateException("Could not read the preserved 40-field dictionary", failure); }
    }

    public static void assertSchema(String name, JsonNode value) {
        assertThat(errors(name, value)).as("Original OpenAPI schema " + name).isEmpty();
    }

    public static List<String> errors(String name, JsonNode value) {
        List<String> errors = new ArrayList<>();
        validate(DOCUMENT.get("components").get("schemas").get(name), value, "", errors);
        return List.copyOf(errors);
    }

    private static JsonNode document() {
        try { return JSON.readTree(JSON.writeValueAsString(new Yaml().load(Files.readString(Path.of("../docs/06_api/openapi.yaml"))))); }
        catch (Exception failure) { throw new IllegalStateException("Could not read the preserved TOM OpenAPI", failure); }
    }

    private static boolean matches(JsonNode schema, JsonNode value) { List<String> errors = new ArrayList<>(); validate(schema, value, "", errors); return errors.isEmpty(); }

    private static void validate(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema == null) { errors.add(path + ": missing schema"); return; }
        if (schema.has("$ref")) validate(DOCUMENT.at(schema.get("$ref").asString().substring(1)), value, path, errors);
        if (schema.has("anyOf")) {
            boolean valid = false; for (JsonNode alternative : schema.get("anyOf")) if (matches(alternative, value)) valid = true;
            if (!valid) errors.add(path + ": anyOf");
        }
        if (schema.has("allOf")) for (JsonNode condition : schema.get("allOf")) validate(condition, value, path, errors);
        if (schema.has("if")) {
            String branch = matches(schema.get("if"), value) ? "then" : "else";
            if (schema.has(branch)) validate(schema.get(branch), value, path, errors);
        }
        if (schema.has("not") && matches(schema.get("not"), value)) errors.add(path + ": not");
        if (schema.has("type")) {
            JsonNode types = schema.get("type"); boolean valid = false;
            if (types.isArray()) { for (JsonNode type : types) valid |= hasType(type.asString(), value); }
            else valid = hasType(types.asString(), value);
            if (!valid) { errors.add(path + ": type"); return; }
        }
        if (schema.has("const") && !same(schema.get("const"), value)) errors.add(path + ": const");
        if (schema.has("enum")) {
            boolean valid = false; for (JsonNode option : schema.get("enum")) valid |= same(option, value);
            if (!valid) errors.add(path + ": enum");
        }
        if (value == null) return;
        if (value.isObject()) {
            if (schema.has("required")) for (JsonNode required : schema.get("required")) if (!value.has(required.asString())) errors.add(path + "/" + required.asString() + ": required");
            JsonNode properties = schema.get("properties");
            for (String name : value.propertyNames()) {
                String child = path + "/" + name.replace("~", "~0").replace("/", "~1");
                if (properties != null && properties.has(name)) validate(properties.get(name), value.get(name), child, errors);
                else if (schema.has("additionalProperties") && schema.get("additionalProperties").isBoolean()
                        && !schema.get("additionalProperties").asBoolean()) errors.add(child + ": additional property");
            }
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) errors.add(path + ": minItems");
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) errors.add(path + ": maxItems");
            for (int index = 0; index < value.size(); index++) {
                if (schema.has("items")) validate(schema.get("items"), value.get(index), path + "/" + index, errors);
                if (schema.has("uniqueItems") && schema.get("uniqueItems").asBoolean()) for (int prior = 0; prior < index; prior++)
                    if (same(value.get(prior), value.get(index))) errors.add(path + ": uniqueItems");
            }
        }
        if (value.isString()) {
            String text = value.asString(); int length = text.codePointCount(0, text.length());
            if (schema.has("minLength") && length < schema.get("minLength").asInt()) errors.add(path + ": minLength");
            if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) errors.add(path + ": maxLength");
            if (schema.has("pattern")) {
                // ECMA whitespace includes FEFF in addition to Java's Unicode whitespace class.
                String regex = schema.get("pattern").asString().replace("\\S", "[^\\s\\uFEFF]");
                if (!Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS).matcher(text).find()) errors.add(path + ": pattern");
            }
            if (schema.has("format") && !validFormat(schema.get("format").asString(), text)) errors.add(path + ": format");
        }
        if (value.isNumber()) {
            BigDecimal number = new BigDecimal(value.toString());
            if (schema.has("minimum") && number.compareTo(new BigDecimal(schema.get("minimum").toString())) < 0) errors.add(path + ": minimum");
            if (schema.has("maximum") && number.compareTo(new BigDecimal(schema.get("maximum").toString())) > 0) errors.add(path + ": maximum");
        }
    }

    private static boolean hasType(String type, JsonNode value) {
        if (value == null) return false;
        return switch (type) {
            case "null" -> value.isNull(); case "string" -> value.isString(); case "object" -> value.isObject();
            case "array" -> value.isArray(); case "boolean" -> value.isBoolean(); case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0;
            default -> throw new IllegalArgumentException("Unsupported contract type: " + type);
        };
    }

    private static boolean same(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
        if (left.isNumber() && right.isNumber()) return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        return left.equals(right);
    }

    private static boolean validFormat(String format, String value) {
        try {
            return switch (format) {
                case "uuid" -> value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
                case "date" -> { if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) yield false; LocalDate.parse(value); yield true; }
                case "date-time" -> {
                    var matcher = Pattern.compile("^(.+[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?)([Zz]|[+-][0-9]{2}:[0-9]{2})$").matcher(value);
                    if (!matcher.matches()) yield false;
                    LocalDateTime.parse(matcher.group(1).replace('t', 'T'));
                    String offset = matcher.group(2);
                    yield offset.equalsIgnoreCase("Z") || Integer.parseInt(offset.substring(1, 3)) <= 23 && Integer.parseInt(offset.substring(4, 6)) <= 59;
                }
                default -> throw new IllegalArgumentException("Unsupported contract format: " + format);
            };
        } catch (java.time.DateTimeException | NumberFormatException invalid) { return false; }
    }
}
