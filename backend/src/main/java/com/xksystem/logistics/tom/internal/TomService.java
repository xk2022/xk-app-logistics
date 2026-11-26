package com.xksystem.logistics.tom.internal;

import com.xksystem.logistics.crm.CrmQueries;
import com.xksystem.logistics.tom.*;
import com.xksystem.logistics.tom.domain.*;
import com.xksystem.logistics.upms.*;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@Service
public class TomService {
    private static final String SCOPE="SINGLE_COMPANY";
    private static final Pattern KEY=Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final TomOrderRepository orders;
    private final UpmsAuthorization authorization;
    private final CrmQueries customers;
    private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<com.xksystem.logistics.tom.AcceptedTaskIndexSink> taskIndexes;
    public TomService(TomOrderRepository orders,UpmsAuthorization authorization,CrmQueries customers,Clock clock,
            org.springframework.beans.factory.ObjectProvider<com.xksystem.logistics.tom.AcceptedTaskIndexSink> taskIndexes){
        this.orders=orders;this.authorization=authorization;this.customers=customers;this.clock=clock;this.taskIndexes=taskIndexes;
    }
    public record WriteResult(int status,ObjectNode data,String location,boolean replayed) {}

    @Transactional
    public WriteResult create(SessionPrincipal principal,String raw,String key,String trace){
        var actor=authorization.requireOrderManager(principal);
        JsonNode original=DraftCodec.parseJson(raw);ObjectNode draft=DraftCodec.createShape(original);
        String hash=DraftCodec.canonicalHash(original);key(key);String path="/api/v1/orders";
        if(!orders.tryKeyLock(SCOPE,actor.id(),"POST",path,key))throw inProgress();
        var prior=orders.replay(SCOPE,actor.id(),"POST",path,key);
        if(prior.isPresent())return replay(principal,prior.get(),hash);
        actor=authorization.requireOrderManagerForWrite(principal);
        String name=customerName(UUID.fromString(draft.get("customerId").asString()));
        DraftCodec.validateGraph(draft);
        UUID id=UUID.randomUUID();Instant now=now();
        try{
            ObjectNode result=orders.create(id,SCOPE,actor.id(),now,draft,name);
            String location=path+"/"+id;orders.revision(id,result,trace,actor.id(),now);
            orders.remember(SCOPE,actor.id(),"POST",path,key,hash,id,201,result,location,now);
            return new WriteResult(201,result,location,false);
        }catch(DataAccessException failure){throw persistence(failure);}
    }
    @Transactional
    public WriteResult replace(SessionPrincipal principal,UUID id,String raw,String key,String trace){
        var actor=authorization.requireOrderManager(principal);
        JsonNode original=DraftCodec.parseJson(raw);DraftCodec.Replacement input=DraftCodec.replaceShape(original);
        ObjectNode draft=input.draft();String hash=DraftCodec.canonicalHash(original);key(key);String path="/api/v1/orders/"+id;
        if(!orders.tryKeyLock(SCOPE,actor.id(),"PUT",path,key))throw inProgress();
        var prior=orders.replay(SCOPE,actor.id(),"PUT",path,key);
        if(prior.isPresent())return replay(principal,prior.get(),hash);
        actor=authorization.requireOrderManagerForWrite(principal);
        ObjectNode observed=orders.read(id,SCOPE,false);
        UUID newCustomer=UUID.fromString(draft.get("customerId").asString());
        String name=newCustomer.equals(UUID.fromString(observed.get("customerId").asString()))?observed.get("customerName").asString():customerName(newCustomer);
        ObjectNode previous=orders.read(id,SCOPE,true);
        long actual=previous.get("version").asLong();
        if(actual!=input.expectedVersion())throw new TomFailure(409,"VERSION_CONFLICT","訂單已由其他操作更新，請重新讀取",Map.of("actualVersion",actual),List.of(new FieldIssue("/expectedVersion","AF01","VERSION","版本已更新")));
        if(!"OPEN".equals(previous.get("lifecycleStatus").asString())||!"DRAFT".equals(previous.get("acceptanceStatus").asString())||actual==DraftCodec.MAX_VERSION)
            throw new TomFailure(409,"ORDER_NOT_EDITABLE","目前訂單不能編輯草稿");
        DraftCodec.validateGraph(draft);Instant now=now();
        try{
            ObjectNode result=orders.replace(id,SCOPE,actor.id(),now,draft,name,previous);
            orders.revision(id,result,trace,actor.id(),now);
            orders.remember(SCOPE,actor.id(),"PUT",path,key,hash,id,200,result,null,now);
            return new WriteResult(200,result,null,false);
        }catch(DataAccessException failure){throw persistence(failure);}
    }
    @Transactional
    public WriteResult accept(SessionPrincipal principal,UUID id,String raw,String key,String trace){
        var actor=authorization.requireOrderManager(principal);
        JsonNode original=DraftCodec.parseJson(raw);var input=AcceptanceRequest.parse(original);
        String hash=DraftCodec.canonicalHash(original);key(key);String path="/api/v1/orders/"+id+"/accept";
        if(!orders.tryKeyLock(SCOPE,actor.id(),"POST",path,key))throw inProgress();
        var prior=orders.replay(SCOPE,actor.id(),"POST",path,key);
        if(prior.isPresent())return replay(principal,prior.get(),hash);

        actor=authorization.requireOrderManagerForWrite(principal);
        ObjectNode observed=orders.read(id,SCOPE,false);
        UUID customerId=UUID.fromString(observed.get("customerId").asString());
        // Hold the current CRM source lock, but preserve the order's original customerName snapshot.
        eligibleCustomer(customerId);
        ObjectNode previous=orders.read(id,SCOPE,true);
        if(!customerId.equals(UUID.fromString(previous.get("customerId").asString())))
            throw new TomFailure(409,"ORDER_NOT_EDITABLE","客戶來源已變更，請重新讀取訂單後再受理");
        long actual=previous.get("version").asLong();
        if(actual!=input.expectedVersion())throw new TomFailure(409,"VERSION_CONFLICT","訂單已由其他操作更新，請重新讀取",
                Map.of("actualVersion",actual),List.of(new FieldIssue("/expectedVersion","AF01","VERSION","版本已更新")));
        if(!"OPEN".equals(previous.get("lifecycleStatus").asString())||!"DRAFT".equals(previous.get("acceptanceStatus").asString())||actual==DraftCodec.MAX_VERSION)
            throw new TomFailure(409,"ORDER_NOT_EDITABLE","目前訂單不能正式受理");
        ObjectNode checked=AcceptanceChecker.check(previous,true);
        if(!"COMPLETE".equals(checked.get("confirmedRulesOutcome").asString())){
            var missing=new ArrayList<FieldIssue>();
            for(JsonNode issue:checked.get("missingFields"))missing.add(new FieldIssue(issue.get("path").asString(),
                    issue.get("fieldId").asString(),issue.get("ruleId").asString(),issue.get("message").asString()));
            throw new TomFailure(400,"VALIDATION_ERROR","訂單尚有已核准規則的必要資料未完成",null,missing);
        }
        Instant now=now();
        try{
            ObjectNode result=orders.accept(id,SCOPE,actor.id(),now,actual);
            orders.revision(id,result,trace,actor.id(),now);
            orders.acceptanceEvent(id,actual,result,trace,actor.id(),now);
            var taskSeeds=new ArrayList<com.xksystem.logistics.tom.TaskQueries.Seed>();
            for(JsonNode task:result.get("tasks"))taskSeeds.add(new com.xksystem.logistics.tom.TaskQueries.Seed(
                    UUID.fromString(task.get("id").asString()),id,result.get("orderNo").asString(),
                    task.get("taskNo").asString(),result.get("version").asLong()));
            var acceptedTasks=List.copyOf(taskSeeds);
            taskIndexes.orderedStream().forEach(index->index.accepted(acceptedTasks));
            orders.remember(SCOPE,actor.id(),"POST",path,key,hash,id,200,result,null,now);
            return new WriteResult(200,result,null,false);
        }catch(DataAccessException failure){throw persistence(failure);}
    }
    private WriteResult replay(SessionPrincipal principal,TomOperations.Replay saved,String hash){
        if(!saved.requestHash().equals(hash))throw new TomFailure(409,"IDEMPOTENCY_KEY_REUSED","此重送識別已用於不同內容");
        // Refresh authorization even when a caller persistence context cached the account before revocation.
        authorization.requireOrderManagerForWrite(principal);
        orders.read(saved.orderId(),SCOPE,false);
        return new WriteResult(saved.status(),saved.data(),saved.location(),true);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ObjectNode get(SessionPrincipal principal,UUID id){authorization.requireOrderManager(principal);return orders.read(id,SCOPE,false);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ObjectNode list(SessionPrincipal principal,TomPageQuery query){authorization.requireOrderManager(principal);return orders.page(SCOPE,query);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ObjectNode check(SessionPrincipal principal,UUID id){
        authorization.requireOrderManager(principal);ObjectNode order=orders.read(id,SCOPE,false);
        return AcceptanceChecker.check(order,customers.isEnabled(UUID.fromString(order.get("customerId").asString())));
    }
    private String customerName(UUID id){return eligibleCustomer(id).name();}
    private CrmQueries.Snapshot eligibleCustomer(UUID id){
        try{return customers.requireEnabledForNewReference(id);}
        catch(UpmsException rejected){if(rejected.status()!=400&&rejected.status()!=404)throw rejected;
            throw new TomFailure(400,"INVALID_CUSTOMER","客戶不存在或不能新增引用",null,List.of(new FieldIssue("/customerId","OF04","ORD-P01","請選擇有效客戶")));}
    }
    private static void key(String value){if(value==null||!KEY.matcher(value).matches())throw new TomFailure(400,"VALIDATION_ERROR","請提供有效 Idempotency-Key",null,List.of(new FieldIssue("/headers/Idempotency-Key","request","FORMAT","重送識別格式不合法")));}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private static TomFailure inProgress(){return new TomFailure(409,"IDEMPOTENCY_IN_PROGRESS","相同重送識別仍在處理，請稍後重試");}
    private static TomFailure persistence(DataAccessException failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&"23503".equals(sql.getSQLState()))return new TomFailure(409,"PROTECTED_RELATION","草稿包含不可移除的受保護關聯");
        return new TomFailure(500,"INTERNAL_ERROR","系統暫時無法完成操作，請稍後重試");
    }
}
