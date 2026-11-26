package com.xksystem.logistics.tom.internal;
import com.xksystem.logistics.tom.OrderReviewQueries;
import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.tom.TomFailure;
import com.xksystem.logistics.tom.closure.ClosureFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class OrderReviewQueryService implements OrderReviewQueries {
 private final TomOrderRepository orders;private final JdbcTemplate jdbc;private final JsonMapper json;
 public OrderReviewQueryService(TomOrderRepository orders,JdbcTemplate jdbc,JsonMapper json){this.orders=orders;this.jdbc=jdbc;this.json=json;}
 public Source find(UUID id){return read(id,false);}
 public Source lockForReview(UUID id){if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalTransactionStateException("Review locks require writable caller transaction");return read(id,true);}
 private Source read(UUID id,boolean lock){try{
  var node=orders.read(id,"SINGLE_COMPANY",lock);var tasks=new ArrayList<TaskQueries.Source>();for(var task:node.get("tasks"))tasks.add(project(node,task));
  boolean intact=false;var receipts=jdbc.queryForList("SELECT snapshot::text FROM logistics.order_acceptance_event WHERE order_id=? AND to_version=?",id,node.get("version").asLong());
  if(!receipts.isEmpty())intact=json.readTree(receipts.getFirst().get("snapshot").toString()).equals(json.readTree(json.writeValueAsString(node)));
  return new Source(id,node.get("orderNo").asString(),node.get("type").asString(),node.get("lifecycleStatus").asString(),node.get("acceptanceStatus").asString(),node.get("version").asLong(),List.copyOf(tasks),intact);
 }catch(TomFailure error){if(error.status()==404)throw ClosureFailure.missing();throw error;}}
 static TaskQueries.Source project(JsonNode order,JsonNode task){JsonNode item=null;for(var candidate:order.get("items"))if(candidate.get("id").equals(task.get("itemId"))){item=candidate;break;}if(item==null)throw ClosureFailure.corrupt();
  var cargo=new TaskQueries.Item(text(item,"itemKind"),text(item,"containerType"),text(item,"containerNo"),item.get("packageQty").isNull()?null:item.get("packageQty").asInt(),text(item,"grossWeight"),text(item,"cbm"));var contact=task.get("siteContact");
  return new TaskQueries.Source(UUID.fromString(task.get("id").asString()),task.get("taskNo").asString(),UUID.fromString(order.get("id").asString()),order.get("orderNo").asString(),order.get("type").asString(),order.get("version").asLong(),cargo,text(task,"taskKind"),text(task,"pickupName"),text(task,"pickupAddress"),text(task,"deliveryName"),text(task,"deliveryAddress"),contact.isNull()?null:new TaskQueries.Contact(text(contact,"name"),text(contact,"phone")),text(task,"plannedStartAt"),text(task,"plannedEndAt"),task.get("requiredTask").isNull()?null:task.get("requiredTask").asBoolean());
 }
 private static String text(JsonNode node,String field){var value=node.get(field);return value==null||value.isNull()?null:value.asString();}
}
