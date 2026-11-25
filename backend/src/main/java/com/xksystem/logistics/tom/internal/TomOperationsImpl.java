package com.xksystem.logistics.tom.internal;

import com.xksystem.logistics.ordercore.OrderCore;
import com.xksystem.logistics.tom.TomFailure;
import com.xksystem.logistics.tom.TomPageQuery;
import com.xksystem.logistics.tom.domain.DraftCodec;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

/** Owned Spring Data fragment. JDBC participates in the same JpaTransactionManager transaction. */
class TomOperationsImpl implements TomOperations {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    TomOperationsImpl(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    private record JsonValue(String value) {}
    @Override public boolean tryKeyLock(String scope,UUID actor,String method,String path,String key){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((scope+"\n"+actor+"\n"+method+"\n"+path+"\n"+key).getBytes(StandardCharsets.UTF_8));
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,ByteBuffer.wrap(digest).getLong()));
        }catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    @Override public Optional<Replay> replay(String scope,UUID actor,String method,String path,String key){
        var rows=jdbc.queryForList("SELECT request_hash,order_id,original_status,original_data::text,original_location FROM logistics.api_idempotency WHERE company_scope=? AND actor_id=? AND http_method=? AND canonical_path=? AND idempotency_key=?",scope,actor,method,path,key);
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
        return Optional.of(new Replay(str(row,"request_hash"),uuid(row,"order_id"),num(row,"original_status").intValue(),(ObjectNode)json.readTree(str(row,"original_data")),str(row,"original_location")));
    }
    @Override public ObjectNode read(UUID id,String scope,boolean lock){
        var rows=jdbc.queryForList("SELECT c.id,c.order_no,c.lifecycle_status,t.* FROM logistics.order_core c JOIN logistics.tom_order t ON t.order_id=c.id WHERE c.id=? AND c.company_scope=?"+(lock?" FOR UPDATE OF c,t":""),id,scope);
        if(rows.isEmpty())throw new TomFailure(404,"ORDER_NOT_FOUND","找不到可存取的訂單");
        var row=rows.getFirst();ObjectNode result=summary(row);
        text(result,"customerReference",decode(row,"customer_reference"));text(result,"remark",decode(row,"remark"));
        if(Boolean.TRUE.equals(row.get("shipping_present"))){
            ObjectNode shipping=json.createObjectNode();
            for(String[] field:new String[][]{{"shippingCompany","shipping_company"},{"vesselVoyage","vessel_voyage"},{"pol","pol"},{"pod","pod"},{"blNo","bl_no"}})text(shipping,field[0],decode(row,field[1]));
            text(shipping,"eta",str(row,"eta_wire"));text(shipping,"etd",str(row,"etd_wire"));result.set("shipping",shipping);
        }else result.putNull("shipping");
        var itemRows=jdbc.queryForList("SELECT * FROM logistics.cargo_item WHERE order_id=? ORDER BY input_position,id",id);
        var taskRows=jdbc.queryForList("SELECT * FROM logistics.transport_task WHERE order_id=? ORDER BY input_position,id",id);
        var edges=jdbc.queryForList("SELECT task_id,predecessor_task_id FROM logistics.task_dependency WHERE order_id=? ORDER BY task_id,input_position,predecessor_task_id",id);
        Map<UUID,String> itemRefs=new HashMap<>(),taskRefs=new HashMap<>();
        for(var item:itemRows)itemRefs.put(uuid(item,"id"),str(item,"client_ref"));
        for(var task:taskRows)taskRefs.put(uuid(task,"id"),str(task,"client_ref"));
        ArrayNode itemArray=json.createArrayNode();
        for(var item:itemRows){
            ObjectNode node=json.createObjectNode();node.put("id",str(item,"id"));node.put("clientRef",str(item,"client_ref"));
            integer(node,"sequence",item.get("sequence"));text(node,"itemKind",str(item,"item_kind"));
            text(node,"containerType",decode(item,"container_type"));text(node,"containerNo",decode(item,"container_no"));
            integer(node,"packageQty",item.get("package_qty"));text(node,"grossWeight",decimal(item,"gross_weight"));text(node,"cbm",decimal(item,"cbm"));
            text(node,"lastPickupDate",str(item,"last_pickup_date_wire"));text(node,"deliveryCutoffAt",str(item,"delivery_cutoff_wire"));itemArray.add(node);
        }
        ArrayNode taskArray=json.createArrayNode();
        for(var task:taskRows){
            UUID taskId=uuid(task,"id");ObjectNode node=json.createObjectNode();node.put("id",taskId.toString());node.put("taskNo",str(task,"task_no"));
            node.put("orderId",id.toString());node.put("itemId",str(task,"item_id"));node.put("clientRef",str(task,"client_ref"));node.put("itemRef",itemRefs.get(uuid(task,"item_id")));
            for(String[] field:new String[][]{{"taskKind","task_kind"},{"pickupName","pickup_name"},{"pickupAddress","pickup_address"},{"deliveryName","delivery_name"},{"deliveryAddress","delivery_address"}})text(node,field[0],decode(task,field[1]));
            if(Boolean.TRUE.equals(task.get("contact_present"))){ObjectNode contact=json.createObjectNode();text(contact,"name",decode(task,"contact_name"));text(contact,"phone",decode(task,"contact_phone"));node.set("siteContact",contact);}else node.putNull("siteContact");
            text(node,"plannedStartAt",str(task,"planned_start_wire"));text(node,"plannedEndAt",str(task,"planned_end_wire"));
            if(task.get("required_task")==null)node.putNull("requiredTask");else node.put("requiredTask",(Boolean)task.get("required_task"));
            ArrayNode refs=json.createArrayNode(),ids=json.createArrayNode();
            for(var edge:edges)if(taskId.equals(uuid(edge,"task_id"))){UUID predecessor=uuid(edge,"predecessor_task_id");refs.add(taskRefs.get(predecessor));ids.add(predecessor.toString());}
            node.set("predecessorRefs",refs);node.set("predecessorTaskIds",ids);taskArray.add(node);
        }
        result.set("items",itemArray);result.set("tasks",taskArray);result.put("createdBy",str(row,"created_by"));result.put("updatedBy",str(row,"updated_by"));return result;
    }
    @Override public ObjectNode page(String scope,TomPageQuery query){
        String from=" FROM logistics.order_core c JOIN logistics.tom_order t ON t.order_id=c.id WHERE c.company_scope=?";
        List<Object> arguments=new ArrayList<>();arguments.add(scope);
        if(query.type()!=null){from+=" AND t.order_type=?";arguments.add(query.type());}
        if(query.customerId()!=null){from+=" AND t.customer_id=?";arguments.add(query.customerId());}
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*)"+from,Long.class,arguments.toArray()));
        ArrayNode content=json.createArrayNode();BigInteger offset=query.page().multiply(BigInteger.valueOf(query.size()));
        if(offset.compareTo(BigInteger.valueOf(count))<0){
            String order=switch(query.sort()){case "updatedAt,asc"->"t.updated_at ASC";case "updatedAt,desc"->"t.updated_at DESC";case "orderNo,asc"->"c.order_no ASC";case "orderNo,desc"->"c.order_no DESC";default->throw new IllegalArgumentException("Unsupported order sort");};
            List<Object> parameters=new ArrayList<>(arguments);parameters.add(query.size());parameters.add(offset.longValueExact());
            for(var row:jdbc.queryForList("SELECT c.id,c.order_no,c.lifecycle_status,t.*"+from+" ORDER BY "+order+",c.id ASC LIMIT ? OFFSET ?",parameters.toArray()))content.add(summary(row));
        }
        ObjectNode page=json.createObjectNode();page.set("content",content);page.put("page",query.page());page.put("size",query.size());page.put("totalElements",count);
        page.put("totalPages",BigInteger.valueOf(count).add(BigInteger.valueOf(query.size()-1L)).divide(BigInteger.valueOf(query.size())));return page;
    }
    @Override public ObjectNode create(UUID id,String scope,UUID actor,Instant now,ObjectNode draft,String name){
        var core=OrderCore.draft(id);var values=audit(actor,now);values.put("id",id);values.put("company_scope",scope);values.put("order_no",core.orderNo());values.put("lifecycle_status",core.lifecycleStatus().name());insert("order_core",values);
        var root=rootFields(draft,name);root.putAll(audit(actor,now));root.put("order_id",id);root.put("acceptance_status","DRAFT");root.put("version",0L);insert("tom_order",root);
        children(id,actor,now,draft,null);return read(id,scope,false);
    }
    @Override public ObjectNode replace(UUID id,String scope,UUID actor,Instant now,ObjectNode draft,String name,ObjectNode previous){
        children(id,actor,now,draft,previous);
        var root=rootFields(draft,name);root.put("version",previous.get("version").asLong()+1);root.put("updated_at",timestamp(now));root.put("updated_by",actor);update("tom_order",root,"order_id=?",id);
        var core=new LinkedHashMap<String,Object>();core.put("updated_at",timestamp(now));core.put("updated_by",actor);update("order_core",core,"id=?",id);return read(id,scope,false);
    }
    private LinkedHashMap<String,Object> rootFields(ObjectNode draft,String name){
        var values=new LinkedHashMap<String,Object>();values.put("order_type",draft.get("type").asString());values.put("customer_id",UUID.fromString(draft.get("customerId").asString()));values.put("customer_name",name);
        values.put("customer_reference",bytes(draft.get("customerReference")));values.put("remark",bytes(draft.get("remark")));
        JsonNode shipping=draft.get("shipping");values.put("shipping_present",!shipping.isNull());
        for(String[] field:new String[][]{{"shippingCompany","shipping_company"},{"vesselVoyage","vessel_voyage"},{"pol","pol"},{"pod","pod"},{"blNo","bl_no"}})values.put(field[1],shipping.isNull()?null:bytes(shipping.get(field[0])));
        for(String field:List.of("eta","etd")){JsonNode value=shipping.isNull()?null:shipping.get(field);values.put(field,dateTime(value));values.put(field+"_wire",wire(value));}return values;
    }
    private void children(UUID id,UUID actor,Instant now,ObjectNode draft,ObjectNode previous){
        Map<String,JsonNode> oldItems=byRef(previous==null?null:previous.get("items")),oldTasks=byRef(previous==null?null:previous.get("tasks"));
        Map<String,UUID> itemIds=new LinkedHashMap<>(),taskIds=new LinkedHashMap<>();
        jdbc.update("DELETE FROM logistics.task_dependency WHERE order_id=?",id);
        int position=0;
        for(JsonNode item:draft.get("items")){
            String ref=item.get("clientRef").asString();JsonNode old=oldItems.get(ref);UUID itemId=old==null?UUID.randomUUID():UUID.fromString(old.get("id").asString());itemIds.put(ref,itemId);
            var values=audit(actor,now);values.put("id",itemId);values.put("order_id",id);values.put("client_ref",ref);values.put("input_position",position++);
            values.put("sequence",nullableInt(item.get("sequence")));values.put("item_kind",wire(item.get("itemKind")));values.put("container_type",bytes(item.get("containerType")));values.put("container_no",bytes(item.get("containerNo")));values.put("package_qty",nullableInt(item.get("packageQty")));
            values.put("gross_weight",money(item.get("grossWeight")));values.put("cbm",money(item.get("cbm")));
            values.put("last_pickup_date",item.get("lastPickupDate").isNull()?null:LocalDate.parse(item.get("lastPickupDate").asString()));values.put("last_pickup_date_wire",wire(item.get("lastPickupDate")));
            values.put("delivery_cutoff_at",dateTime(item.get("deliveryCutoffAt")));values.put("delivery_cutoff_wire",wire(item.get("deliveryCutoffAt")));upsert("cargo_item",values);
        }
        position=0;
        for(JsonNode task:draft.get("tasks")){
            String ref=task.get("clientRef").asString();JsonNode old=oldTasks.get(ref);UUID taskId=old==null?UUID.randomUUID():UUID.fromString(old.get("id").asString());taskIds.put(ref,taskId);
            var values=audit(actor,now);values.put("id",taskId);values.put("task_no",old==null?OrderCore.taskNo(taskId):old.get("taskNo").asString());values.put("order_id",id);values.put("item_id",itemIds.get(task.get("itemRef").asString()));values.put("client_ref",ref);values.put("input_position",position++);
            for(String[] field:new String[][]{{"taskKind","task_kind"},{"pickupName","pickup_name"},{"pickupAddress","pickup_address"},{"deliveryName","delivery_name"},{"deliveryAddress","delivery_address"}})values.put(field[1],bytes(task.get(field[0])));
            JsonNode contact=task.get("siteContact");values.put("contact_present",!contact.isNull());values.put("contact_name",contact.isNull()?null:bytes(contact.get("name")));values.put("contact_phone",contact.isNull()?null:bytes(contact.get("phone")));
            values.put("planned_start_at",dateTime(task.get("plannedStartAt")));values.put("planned_start_wire",wire(task.get("plannedStartAt")));values.put("planned_end_at",dateTime(task.get("plannedEndAt")));values.put("planned_end_wire",wire(task.get("plannedEndAt")));values.put("required_task",task.get("requiredTask").isNull()?null:task.get("requiredTask").asBoolean());upsert("transport_task",values);
        }
        deleteRemoved("transport_task",id,taskIds.values());deleteRemoved("cargo_item",id,itemIds.values());
        for(JsonNode task:draft.get("tasks")){position=0;for(JsonNode predecessor:task.get("predecessorRefs")){
            var values=audit(actor,now);values.put("order_id",id);values.put("task_id",taskIds.get(task.get("clientRef").asString()));values.put("predecessor_task_id",taskIds.get(predecessor.asString()));values.put("input_position",position++);insert("task_dependency",values);
        }}
    }
    @Override public void revision(UUID id,ObjectNode result,String trace,UUID actor,Instant now){
        var values=audit(actor,now);values.put("id",UUID.randomUUID());values.put("order_id",id);values.put("aggregate_version",result.get("version").asLong());values.put("snapshot",new JsonValue(json.writeValueAsString(result)));values.put("request_trace_id",trace);insert("order_draft_revision",values);
    }
    @Override public void remember(String scope,UUID actor,String method,String path,String key,String hash,UUID id,int status,ObjectNode data,String location,Instant now){
        var values=audit(actor,now);values.put("id",UUID.randomUUID());values.put("company_scope",scope);values.put("actor_id",actor);values.put("http_method",method);values.put("canonical_path",path);values.put("idempotency_key",key);values.put("request_hash",hash);values.put("order_id",id);values.put("original_status",status);values.put("original_data",new JsonValue(json.writeValueAsString(data)));values.put("original_location",location);insert("api_idempotency",values);
    }
    private ObjectNode summary(Map<String,Object> row){
        ObjectNode value=json.createObjectNode();value.put("id",str(row,"id"));value.put("orderNo",str(row,"order_no"));value.put("type",str(row,"order_type"));value.put("customerId",str(row,"customer_id"));value.put("customerName",str(row,"customer_name"));value.put("lifecycleStatus",str(row,"lifecycle_status"));value.put("acceptanceStatus",str(row,"acceptance_status"));value.put("version",num(row,"version").longValue());value.put("createdAt",((Timestamp)row.get("created_at")).toInstant().toString());value.put("updatedAt",((Timestamp)row.get("updated_at")).toInstant().toString());return value;
    }
    private LinkedHashMap<String,Object> audit(UUID actor,Instant now){var values=new LinkedHashMap<String,Object>();values.put("created_at",timestamp(now));values.put("updated_at",timestamp(now));values.put("created_by",actor);values.put("updated_by",actor);return values;}
    private void insert(String table,LinkedHashMap<String,Object> values){write(table,values,false);}
    private void upsert(String table,LinkedHashMap<String,Object> values){write(table,values,true);}
    private void write(String table,LinkedHashMap<String,Object> values,boolean upsert){
        String columns=String.join(",",values.keySet());List<String> slots=new ArrayList<>();List<Object> parameters=new ArrayList<>();
        values.values().forEach(value->{slots.add(value instanceof JsonValue?"CAST(? AS json)":"?");parameters.add(value instanceof JsonValue encoded?encoded.value():value);});
        String sql="INSERT INTO logistics."+table+" ("+columns+") VALUES ("+String.join(",",slots)+")";
        if(upsert){List<String> assignments=new ArrayList<>();for(String column:values.keySet())if(!Set.of("id","created_at","created_by").contains(column))assignments.add(column+"=EXCLUDED."+column);sql+=" ON CONFLICT(id) DO UPDATE SET "+String.join(",",assignments)+" WHERE "+table+".order_id=EXCLUDED.order_id";}
        if(jdbc.update(sql,parameters.toArray())!=1)throw new IllegalStateException("Aggregate write did not affect exactly one owned row");
    }
    private void update(String table,LinkedHashMap<String,Object> values,String where,Object...criteria){List<String> assignments=new ArrayList<>();values.keySet().forEach(column->assignments.add(column+"=?"));List<Object> parameters=new ArrayList<>(values.values());parameters.addAll(Arrays.asList(criteria));jdbc.update("UPDATE logistics."+table+" SET "+String.join(",",assignments)+" WHERE "+where,parameters.toArray());}
    private void deleteRemoved(String table,UUID orderId,Collection<UUID> ids){String sql="DELETE FROM logistics."+table+" WHERE order_id=?";List<Object> args=new ArrayList<>();args.add(orderId);if(!ids.isEmpty()){sql+=" AND id NOT IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";args.addAll(ids);}jdbc.update(sql,args.toArray());}
    private static Map<String,JsonNode> byRef(JsonNode array){Map<String,JsonNode> result=new LinkedHashMap<>();if(array!=null)for(JsonNode value:array)result.put(value.get("clientRef").asString(),value);return result;}
    private static byte[] bytes(JsonNode node){return node==null||node.isNull()?null:node.asString().getBytes(StandardCharsets.UTF_8);}
    private static String decode(Map<String,Object> row,String key){byte[] value=(byte[])row.get(key);if(value==null)return null;try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();}catch(CharacterCodingException failure){throw new IllegalStateException("Invalid stored UTF-8",failure);}}
    private static String wire(JsonNode node){return node==null||node.isNull()?null:node.asString();}
    // JDBC 4.2 OffsetDateTime preserves proleptic Gregorian instants. Timestamp's
    // legacy Calendar formatting shifts valid pre-1582 dates in the PostgreSQL driver.
    private static OffsetDateTime dateTime(JsonNode node){return node==null||node.isNull()?null:DraftCodec.parseInstant(node.asString()).atOffset(ZoneOffset.UTC);}
    private static Timestamp timestamp(Instant value){return Timestamp.from(value);}
    private static BigDecimal money(JsonNode node){return node==null||node.isNull()?null:new BigDecimal(node.asString());}
    private static Integer nullableInt(JsonNode node){return node==null||node.isNull()?null:node.asInt();}
    private static UUID uuid(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof UUID id?id:UUID.fromString(value.toString());}
    private static String str(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value.toString();}
    private static Number num(Map<String,Object> row,String key){return (Number)row.get(key);}
    private static String decimal(Map<String,Object> row,String key){BigDecimal value=(BigDecimal)row.get(key);return value==null?null:value.stripTrailingZeros().toPlainString();}
    private static void text(ObjectNode node,String field,String value){if(value==null)node.putNull(field);else node.put(field,value);}
    private static void integer(ObjectNode node,String field,Object value){if(value==null)node.putNull(field);else node.put(field,((Number)value).intValue());}
}
