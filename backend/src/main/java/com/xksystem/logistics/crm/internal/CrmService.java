package com.xksystem.logistics.crm.internal;

import com.xksystem.logistics.crm.*;
import com.xksystem.logistics.upms.*;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

@Service
@Transactional(readOnly=true)
public class CrmService implements CrmQueries {
    private final CustomerRepository customers;
    private final ContactRepository contacts;
    private final AddressRepository addresses;
    private final UpmsAuthorization authorization;
    private final Clock clock;
    @PersistenceContext private EntityManager entityManager;
    public CrmService(CustomerRepository customers,ContactRepository contacts,AddressRepository addresses,
                      UpmsAuthorization authorization,Clock clock) {
        this.customers=customers;this.contacts=contacts;this.addresses=addresses;this.authorization=authorization;this.clock=clock;
    }
    public CrmDtos.PageData<CrmDtos.Customer> customers(SessionPrincipal principal,CrmPageQuery query) {
        authorization.requireCustomerManager(principal);
        return CrmDtos.PageData.from(customers.findAll(filter(query,null,"customerCode","name","taxId"),query.pageable()).map(CustomerEntity::dto));
    }
    public CrmDtos.Customer customer(SessionPrincipal principal,UUID id) {
        authorization.requireCustomerManager(principal);return customer(id,false).dto();
    }
    public CrmDtos.PageData<CrmDtos.Contact> contacts(SessionPrincipal principal,UUID customerId,CrmPageQuery query) {
        authorization.requireCustomerManager(principal);customer(customerId,false);
        return CrmDtos.PageData.from(contacts.findAll(filter(query,customerId,"name","title","phone","email"),query.pageable()).map(ContactEntity::dto));
    }
    public CrmDtos.PageData<CrmDtos.Address> addresses(SessionPrincipal principal,UUID customerId,CrmPageQuery query) {
        authorization.requireCustomerManager(principal);customer(customerId,false);
        return CrmDtos.PageData.from(addresses.findAll(filter(query,customerId,"label","address","addressType"),query.pageable()).map(AddressEntity::dto));
    }
    public CrmDtos.PageData<CrmDtos.CustomerOption> options(SessionPrincipal principal,CrmPageQuery query) {
        authorization.requireCustomerManager(principal);
        // Enforce this invariant even for trusted code that constructs a query directly.
        Specification<CustomerEntity> active=(root,q,cb)->cb.isTrue(root.get("enabled"));
        return CrmDtos.PageData.from(customers.findAll(active.and(filter(query,null,"customerCode","name","taxId")),query.pageable()).map(CustomerEntity::option));
    }

    @Transactional
    public CrmDtos.Customer createCustomer(SessionPrincipal principal,JsonNode body) {
        var actor=authorization.requireCustomerManagerForWrite(principal);
        String code=CrmValidation.customerCode(body);
        if(customers.findByCustomerCode(code).isPresent())throw new UpmsException(409,"客戶代碼已存在，請選擇其他代碼");
        var entity=new CustomerEntity();entity.id=UUID.randomUUID();entity.customerCode=code;
        entity.name=CrmValidation.required(body,"name",200,false);
        entity.taxId=CrmValidation.optional(body,"taxId",50);entity.phone=CrmValidation.optional(body,"phone",50);entity.email=CrmValidation.email(body);
        entity.enabled=!body.has("enabled")||CrmValidation.bool(body,"enabled");
        entity.createdAt=clock.instant();entity.updatedAt=clock.instant();entity.createdBy=actor.id();entity.updatedBy=actor.id();
        return customers.saveAndFlush(entity).dto();
    }
    @Transactional
    public CrmDtos.Customer updateCustomer(SessionPrincipal principal,UUID id,JsonNode body) {
        var actor=authorization.requireCustomerManagerForWrite(principal);
        var entity=customer(id,true);version(entity.version,CrmValidation.version(body));
        if(body.has("name"))entity.name=CrmValidation.required(body,"name",200,false);
        if(body.has("taxId"))entity.taxId=CrmValidation.optional(body,"taxId",50);
        if(body.has("phone"))entity.phone=CrmValidation.optional(body,"phone",50);
        if(body.has("email"))entity.email=CrmValidation.email(body);
        if(body.has("enabled"))entity.enabled=CrmValidation.bool(body,"enabled");
        entity.updatedAt=clock.instant();entity.updatedBy=actor.id();customers.flush();return entity.dto();
    }
    @Transactional
    public CrmDtos.Contact createContact(SessionPrincipal principal,UUID parentId,JsonNode body) {
        var actor=authorization.requireCustomerManagerForWrite(principal);customer(parentId,true);
        var entity=new ContactEntity();entity.id=UUID.randomUUID();entity.customerId=parentId;
        entity.name=CrmValidation.required(body,"name",200,false);entity.title=CrmValidation.optional(body,"title",100);
        entity.phone=CrmValidation.optional(body,"phone",50);entity.email=CrmValidation.email(body);
        entity.enabled=!body.has("enabled")||CrmValidation.bool(body,"enabled");
        entity.createdAt=clock.instant();entity.updatedAt=clock.instant();entity.createdBy=actor.id();entity.updatedBy=actor.id();
        return contacts.saveAndFlush(entity).dto();
    }
    @Transactional
    public CrmDtos.Contact updateContact(SessionPrincipal principal,UUID parentId,UUID id,JsonNode body) {
        var actor=authorization.requireCustomerManagerForWrite(principal);customer(parentId,true);
        var entity=contacts.locked(parentId,id).orElseThrow(()->missing("聯絡人"));version(entity.version,CrmValidation.version(body));
        if(body.has("name"))entity.name=CrmValidation.required(body,"name",200,false);
        if(body.has("title"))entity.title=CrmValidation.optional(body,"title",100);
        if(body.has("phone"))entity.phone=CrmValidation.optional(body,"phone",50);
        if(body.has("email"))entity.email=CrmValidation.email(body);
        if(body.has("enabled"))entity.enabled=CrmValidation.bool(body,"enabled");
        entity.updatedAt=clock.instant();entity.updatedBy=actor.id();contacts.flush();return entity.dto();
    }
    @Transactional
    public CrmDtos.Address createAddress(SessionPrincipal principal,UUID parentId,JsonNode body) {
        var actor=authorization.requireCustomerManagerForWrite(principal);customer(parentId,true);
        var entity=new AddressEntity();entity.id=UUID.randomUUID();entity.customerId=parentId;
        entity.label=CrmValidation.optional(body,"label",100);entity.address=CrmValidation.required(body,"address",1000,true);
        entity.addressType=CrmValidation.optional(body,"addressType",40);
        entity.enabled=!body.has("enabled")||CrmValidation.bool(body,"enabled");
        entity.createdAt=clock.instant();entity.updatedAt=clock.instant();entity.createdBy=actor.id();entity.updatedBy=actor.id();
        return addresses.saveAndFlush(entity).dto();
    }
    @Transactional
    public CrmDtos.Address updateAddress(SessionPrincipal principal,UUID parentId,UUID id,JsonNode body) {
        var actor=authorization.requireCustomerManagerForWrite(principal);customer(parentId,true);
        var entity=addresses.locked(parentId,id).orElseThrow(()->missing("地址"));version(entity.version,CrmValidation.version(body));
        if(body.has("label"))entity.label=CrmValidation.optional(body,"label",100);
        if(body.has("address"))entity.address=CrmValidation.required(body,"address",1000,true);
        if(body.has("addressType"))entity.addressType=CrmValidation.optional(body,"addressType",40);
        if(body.has("enabled"))entity.enabled=CrmValidation.bool(body,"enabled");
        entity.updatedAt=clock.instant();entity.updatedBy=actor.id();addresses.flush();return entity.dto();
    }

    @Override
    public Optional<Snapshot> findCurrent(UUID customerId) { return customers.findById(customerId).map(this::snapshot); }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Snapshot requireEnabledForNewReference(UUID customerId) {
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("CRM new references require a writable caller transaction");
        var entity=customer(customerId,true);
        if(!entity.enabled)throw UpmsException.field("customerId","客戶已停用，不能新增引用");
        return snapshot(entity);
    }
    private Snapshot snapshot(CustomerEntity entity) {
        // Preserve the exact validated source value rather than silently repairing historical data.
        String normalized=CrmValidation.text(entity.name,"customerId",1,200,false);
        if(!normalized.equals(entity.name)||!entity.customerCode.matches("[A-Z0-9][A-Z0-9_-]{0,63}"))
            throw UpmsException.field("customerId","客戶資料不符合引用規格，請由管理員複核");
        return entity.snapshot();
    }
    private CustomerEntity customer(UUID id,boolean write) {
        var entity=customers.findById(id).orElseThrow(()->missing("客戶"));
        // A caller may have read this entity earlier in the same persistence context.
        // A versioned locking query can reject stale managed state before refresh runs.
        // Refresh itself must acquire the lock and reload the current version atomically.
        if(write)entityManager.refresh(entity,LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }
    private static UpmsException missing(String target){return new UpmsException(404,"找不到"+target);}
    private static void version(long actual,long expected){if(actual!=expected)throw new UpmsException(409,"資料已由其他操作更新，請重新讀取後再試");}
    private static <T> Specification<T> filter(CrmPageQuery query,UUID parent,String...fields) {
        return(root,q,cb)-> {
            var predicates=new ArrayList<Predicate>();
            if(parent!=null)predicates.add(cb.equal(root.get("customerId"),parent));
            if(query.enabled()!=null)predicates.add(cb.equal(root.get("enabled"),query.enabled()));
            if(!query.q().isEmpty()) {
                String literal=query.q().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
                var matches=new ArrayList<Predicate>();
                for(String field:fields)matches.add(cb.like(cb.lower(root.get(field)),"%"+literal+"%",'\\'));
                predicates.add(cb.or(matches.toArray(Predicate[]::new)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
