package com.xksystem.logistics.tom.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Read-only ORD-P01–P03 completeness. It never grants operational acceptance. */
public final class AcceptanceChecker {
    private AcceptanceChecker() {}
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final List<String> PENDING = List.of("D04_CONTACT", "D04_CONTAINER_FORMAT", "D04_WEIGHT",
            "D04_DICTIONARY", "D04_DEADLINE_EXCEPTION", "D05_REQUIRED_TASK_WAIVER");

    public static ObjectNode check(JsonNode order, boolean customerEligible) {
        List<FieldIssue> missing = new ArrayList<>();
        String type = order.path("type").asString("");
        if (!List.of("IMPORT", "EXPORT", "LOCAL").contains(type))
            add(missing, "/type", "OF03", "ORD-P01", "請提供有效訂單類型");
        if (!customerEligible)
            add(missing, "/customerId", "OF04", "ORD-P01", "客戶不存在或已停用，不能新增引用");

        JsonNode items = order.path("items");
        JsonNode tasks = order.path("tasks");
        if (items.size() == 0) add(missing, "/items", "ORD-P02", "ORD-P02", "受理欄位至少須有一個項目");
        Map<String, Boolean> requiredByItem = new HashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            JsonNode task = tasks.get(i);
            if (task.path("requiredTask").isBoolean() && task.path("requiredTask").asBoolean())
                requiredByItem.put(task.path("itemRef").asString(""), true);
            String path = "/tasks/" + i;
            require(missing, task, "taskKind", path, "TF05", "ORD-P02", "請補作業類型");
            require(missing, task, "pickupName", path, "TF06", "ORD-P02", "請補起點名稱");
            require(missing, task, "pickupAddress", path, "TF07", "ORD-P02", "請補起點地址");
            require(missing, task, "deliveryName", path, "TF08", "ORD-P02", "請補終點名稱");
            require(missing, task, "deliveryAddress", path, "TF09", "ORD-P02", "請補終點地址");
        }
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String path = "/items/" + i;
            require(missing, item, "sequence", path, "IF02", "ORD-P02", "請補項目序號");
            require(missing, item, "itemKind", path, "IF03", "ORD-P02", "請補標的類型");
            String kind = item.path("itemKind").asString("");
            if (kind.equals("CONTAINER")) {
                require(missing, item, "containerType", path, "IF04", "ORD-P02", "請補貨櫃類型");
                if (type.equals("IMPORT")) require(missing, item, "lastPickupDate", path, "IF09", "ORD-P03", "請補最後提櫃日");
                if (type.equals("EXPORT")) require(missing, item, "deliveryCutoffAt", path, "IF10", "ORD-P03", "請補交櫃截止時間");
            }
            if (type.equals("LOCAL") && kind.equals("CARGO"))
                require(missing, item, "packageQty", path, "IF06", "ORD-P02", "請補一般貨物件數");
            if (!requiredByItem.containsKey(item.path("clientRef").asString("")))
                add(missing, path, "ORD-P02", "ORD-P02", "每個項目至少須有一段標為必要的任務");
        }
        if (type.equals("IMPORT") || type.equals("EXPORT")) {
            JsonNode shipping = order.path("shipping");
            require(missing, shipping, "shippingCompany", "/shipping", "SF01", "ORD-P03", "請補船公司");
            require(missing, shipping, "vesselVoyage", "/shipping", "SF02", "ORD-P03", "請補船名航次");
            require(missing, shipping, "pol", "/shipping", "SF03", "ORD-P03", "請補裝貨港");
            require(missing, shipping, "pod", "/shipping", "SF04", "ORD-P03", "請補卸貨港");
            if (type.equals("IMPORT")) {
                require(missing, shipping, "eta", "/shipping", "SF05", "ORD-P03", "請補預計到港時間");
                require(missing, shipping, "blNo", "/shipping", "SF07", "ORD-P03", "請補提單號");
            } else require(missing, shipping, "etd", "/shipping", "SF06", "ORD-P03", "請補預計離港時間");
        }
        missing.addAll(DraftCodec.graphIssues(order));
        // Multiple checks may point to the same field; keep a deterministic single issue per rule/location.
        Map<String, FieldIssue> unique = new LinkedHashMap<>();
        for (FieldIssue issue : missing) unique.putIfAbsent(issue.path() + "\u0000" + issue.fieldId() + "\u0000" + issue.ruleId(), issue);

        ObjectNode result = NODES.objectNode();
        result.set("orderId", order.path("id"));
        result.set("version", order.path("version"));
        ArrayNode checked = result.putArray("checkedRules");
        checked.add("ORD-P01").add("ORD-P02").add("ORD-P03");
        result.put("confirmedRulesOutcome", unique.isEmpty() ? "COMPLETE" : "INCOMPLETE");
        ArrayNode issues = result.putArray("missingFields");
        for (FieldIssue issue : unique.values()) {
            ObjectNode value = NODES.objectNode();
            value.put("path", issue.path()); value.put("fieldId", issue.fieldId());
            value.put("ruleId", issue.ruleId()); value.put("message", issue.message());
            issues.add(value);
        }
        ArrayNode pending = result.putArray("pendingPolicyIds");
        PENDING.forEach(pending::add);
        result.put("operationalAcceptance", "NOT_GRANTED");
        return result;
    }

    private static void require(List<FieldIssue> output, JsonNode parent, String field, String path,
                                String fieldId, String rule, String message) {
        JsonNode value = parent.path(field);
        if (value.isNull() || value.isMissingNode() || value.isString() && !DraftCodec.hasNonWhitespace(value.asString()))
            add(output, path + "/" + field, fieldId, rule, message);
    }

    private static void add(List<FieldIssue> output, String path, String fieldId, String rule, String message) {
        output.add(new FieldIssue(path, fieldId, rule, message));
    }
}
