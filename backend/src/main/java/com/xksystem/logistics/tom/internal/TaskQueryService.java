package com.xksystem.logistics.tom.internal;

import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.tom.TomFailure;
import com.xksystem.logistics.upms.UpmsException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** TOM owns the source persistence; callers receive only the agreed dispatch projection. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class TaskQueryService implements TaskQueries {
    private static final String SCOPE="SINGLE_COMPANY";
    private final JdbcTemplate jdbc;
    private final TomOrderRepository orders;

    public TaskQueryService(JdbcTemplate jdbc,TomOrderRepository orders) {
        this.jdbc=jdbc;this.orders=orders;
    }

    @Override public Optional<Current> find(UUID taskId) {
        return taskId==null?Optional.empty():Optional.ofNullable(bulk(List.of(taskId)).get(taskId));
    }

    @Override public Map<UUID,Current> bulk(List<UUID> taskIds) {
        if(taskIds==null||taskIds.size()>100||taskIds.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Task source lookup requires at most 100 non-null IDs");
        var unique=new LinkedHashSet<>(taskIds);
        if(unique.isEmpty())return Map.of();
        var parameters=new ArrayList<Object>(unique);parameters.add(SCOPE);
        String placeholders=String.join(",",Collections.nCopies(unique.size(),"?"));
        var identities=jdbc.query("SELECT t.id,t.order_id FROM logistics.transport_task t "
                +"JOIN logistics.order_core c ON c.id=t.order_id WHERE t.id IN ("+placeholders+") "
                +"AND c.company_scope=? ORDER BY t.order_id,t.id",
                (row,index)->Map.entry(row.getObject(1,UUID.class),row.getObject(2,UUID.class)),parameters.toArray());
        var roots=new LinkedHashMap<UUID,JsonNode>();
        var result=new LinkedHashMap<UUID,Current>();
        for(var identity:identities) {
            JsonNode order=roots.computeIfAbsent(identity.getValue(),id->orders.read(id,SCOPE,false));
            project(order,identity.getKey()).ifPresent(value->result.put(identity.getKey(),value));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Current lockCurrentForDispatch(UUID taskId) {
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Task source locks require a writable caller transaction");
        if(taskId==null)throw missing();
        var orderIds=jdbc.query("SELECT t.order_id FROM logistics.transport_task t "
                +"JOIN logistics.order_core c ON c.id=t.order_id WHERE t.id=? AND c.company_scope=?",
                (row,index)->row.getObject(1,UUID.class),taskId,SCOPE);
        if(orderIds.isEmpty())throw missing();
        UUID orderId=orderIds.getFirst();
        try {
            // Match the existing TOM root lock order, then lock the selected child source.
            // Re-read only after the locks; the initial lookup is an identity hint, not a snapshot.
            orders.read(orderId,SCOPE,true);
            var itemIds=jdbc.query("SELECT item_id FROM logistics.transport_task WHERE order_id=? AND id=? FOR UPDATE",
                    (row,index)->row.getObject(1,UUID.class),orderId,taskId);
            if(itemIds.isEmpty())throw missing();
            jdbc.query("SELECT id FROM logistics.cargo_item WHERE order_id=? AND id=? FOR UPDATE",
                    (row,index)->row.getObject(1,UUID.class),orderId,itemIds.getFirst());
            return project(orders.read(orderId,SCOPE,false),taskId).orElseThrow(TaskQueryService::missing);
        } catch(TomFailure failure) {
            if(failure.status()==404)throw missing();
            throw failure;
        }
    }

    private Optional<Current> project(JsonNode order,UUID taskId) {
        JsonNode selected=null;
        for(JsonNode task:order.get("tasks"))if(taskId.toString().equals(task.get("id").asString())){selected=task;break;}
        if(selected==null)return Optional.empty();
        JsonNode item=null;
        for(JsonNode candidate:order.get("items"))if(candidate.get("id").equals(selected.get("itemId"))){item=candidate;break;}
        if(item==null)throw new IllegalStateException("Task source references a missing cargo item");
        var target=new Item(text(item,"itemKind"),text(item,"containerType"),text(item,"containerNo"),
                item.get("packageQty").isNull()?null:item.get("packageQty").asInt(),text(item,"grossWeight"),text(item,"cbm"));
        JsonNode contact=selected.get("siteContact");
        Contact siteContact=contact.isNull()?null:new Contact(text(contact,"name"),text(contact,"phone"));
        var source=new Source(taskId,selected.get("taskNo").asString(),UUID.fromString(order.get("id").asString()),
                order.get("orderNo").asString(),order.get("type").asString(),order.get("version").asLong(),target,
                text(selected,"taskKind"),text(selected,"pickupName"),text(selected,"pickupAddress"),
                text(selected,"deliveryName"),text(selected,"deliveryAddress"),siteContact,
                text(selected,"plannedStartAt"),text(selected,"plannedEndAt"),
                selected.get("requiredTask").isNull()?null:selected.get("requiredTask").asBoolean());
        boolean accepted="OPEN".equals(order.get("lifecycleStatus").asString())
                &&"ACCEPTED".equals(order.get("acceptanceStatus").asString());
        // There is no M4 completion/handover evidence yet. Releasing a dispatch is not completion.
        JsonNode predecessors=selected.get("predecessorTaskIds");
        boolean ready=predecessors!=null&&predecessors.isArray()&&predecessors.size()==0;
        return Optional.of(new Current(source,accepted,ready));
    }

    private static String text(JsonNode value,String name) {
        JsonNode field=value.get(name);return field==null||field.isNull()?null:field.asString();
    }
    private static UpmsException missing(){return new UpmsException(404,"找不到任務來源");}
}
