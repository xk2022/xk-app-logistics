package com.xksystem.logistics.fms.internal;

import com.xksystem.logistics.adm.AdmQueries;
import com.xksystem.logistics.fms.*;
import com.xksystem.logistics.upms.DriverAccountQueries;
import com.xksystem.logistics.upms.UpmsAuthorization;
import com.xksystem.logistics.upms.UpmsException;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

@Service @Transactional(readOnly=true)
public class DriverService implements DriverQueries {
    private static final String LICENSE_TYPE="DRIVER_LICENSE_TYPE";
    private final DriverRepository drivers;
    private final AdmQueries dictionaries;
    private final DriverAccountQueries accounts;
    private final UpmsAuthorization authorization;
    private final Clock clock;
    @PersistenceContext private EntityManager entityManager;
    public DriverService(DriverRepository drivers,AdmQueries dictionaries,DriverAccountQueries accounts,UpmsAuthorization authorization,Clock clock) {
        this.drivers=drivers;this.dictionaries=dictionaries;this.accounts=accounts;this.authorization=authorization;this.clock=clock;
    }
    public DriverDtos.PageData<DriverDtos.DriverSummary> drivers(SessionPrincipal principal,DriverPageQuery query) {
        authorization.requireDriverManager(principal);
        return DriverDtos.PageData.from(drivers.findAll(filter(query,false),query.pageable()).map(this::summary));
    }
    public DriverDtos.Driver driver(SessionPrincipal principal,UUID id) {
        authorization.requireDriverManager(principal);return dto(driver(id,false));
    }
    public List<DriverDtos.DriverLicenseType> licenseTypes(SessionPrincipal principal) {
        authorization.requireDriverManager(principal);
        return dictionaries.enabledOptions(LICENSE_TYPE).stream().map(type->new DriverDtos.DriverLicenseType(type.id(),type.code(),type.label())).toList();
    }
    public DriverDtos.PageData<DriverDtos.DriverOption> options(SessionPrincipal principal,DriverPageQuery query) {
        authorization.requireDriverManager(principal);
        return DriverDtos.PageData.from(drivers.findAll(filter(query,true),query.pageable()).map(entity->{
            var type=license(entity.licenseType);
            return new DriverDtos.DriverOption(entity.id,entity.driverCode,entity.name,entity.licenseType,type.code(),type.label(),type.enabled(),entity.licenseDueDate,entity.version);
        }));
    }
    public DriverDtos.PageData<DriverDtos.DriverAccountOption> accountOptions(SessionPrincipal principal,DriverPageQuery query) {
        authorization.requireDriverManager(principal);
        var page=accounts.candidates(query.pageable().getPageNumber(),query.pageable().getPageSize(),query.q());
        var ids=page.getContent().stream().map(DriverAccountQueries.Candidate::id).toList();
        Set<UUID> bound=ids.isEmpty()?Set.of():new HashSet<>(drivers.boundAccountIds(ids));
        // Keep the UPMS candidate page/count intact. A bound flag reveals no other driver identity.
        return DriverDtos.PageData.from(page.map(account->new DriverDtos.DriverAccountOption(account.id(),account.username(),account.displayName(),bound.contains(account.id()))));
    }
    @Transactional
    public DriverDtos.Driver create(SessionPrincipal principal,JsonNode body) {
        var actor=authorization.requireDriverManagerForWrite(principal);DriverValidation.fields(body,false);
        String code=DriverValidation.driverCode(body);if(drivers.existsByDriverCode(code))throw duplicateCode();
        UUID account=DriverValidation.optionalUuid(body,"accountId");bind(account,null,null);
        UUID type=DriverValidation.optionalUuid(body,"licenseType");if(type!=null)activeLicense(type);
        var entity=new DriverEntity();entity.id=UUID.randomUUID();entity.driverCode=code;entity.name=DriverValidation.name(body);
        entity.phone=DriverValidation.optionalText(body,"phone",50);entity.email=DriverValidation.optionalText(body,"email",254);
        entity.accountId=account;entity.licenseType=type;entity.licenseReference=DriverValidation.optionalText(body,"licenseReference",100);
        entity.licenseDueDate=DriverValidation.date(body,"licenseDueDate");
        entity.employmentStatus=body.has("employmentStatus")?DriverValidation.employmentStatus(body):EmploymentStatus.ACTIVE;
        entity.dutyStatus=body.has("dutyStatus")?DriverValidation.dutyStatus(body):DutyStatus.OFF_DUTY;
        entity.createdAt=clock.instant();entity.updatedAt=entity.createdAt;entity.createdBy=actor.id();entity.updatedBy=actor.id();
        return dto(drivers.saveAndFlush(entity));
    }
    @Transactional
    public DriverDtos.Driver update(SessionPrincipal principal,UUID id,JsonNode body) {
        var actor=authorization.requireDriverManagerForWrite(principal);DriverValidation.fields(body,true);
        var entity=driver(id,true);long expected=DriverValidation.version(body);
        if(entity.version!=expected)throw new UpmsException(409,"資料已由其他操作更新，請重新讀取後再試",Map.of("version","版本已更新，請重新讀取後再試"));
        if(entity.version>=DriverValidation.MAX_VERSION)throw new UpmsException(409,"資料版本已達上限，請由管理員處理",Map.of("version","版本已達上限，請由管理員處理"));
        if(body.has("employmentStatus")) {
            var next=DriverValidation.employmentStatus(body);
            if(entity.employmentStatus==EmploymentStatus.LEFT&&next!=EmploymentStatus.LEFT)
                throw new UpmsException(409,"離職資料的復職政策尚待核准",Map.of("employmentStatus","LEFT 目前不可轉回其他在職狀態"));
            entity.employmentStatus=next;
        }
        if(body.has("accountId")) {
            UUID next=DriverValidation.optionalUuid(body,"accountId");bind(next,entity.accountId,entity.id);entity.accountId=next;
        }
        if(body.has("licenseType")) {
            UUID next=DriverValidation.optionalUuid(body,"licenseType");
            if(next!=null&&!next.equals(entity.licenseType))activeLicense(next);entity.licenseType=next;
        }
        if(body.has("name"))entity.name=DriverValidation.name(body);
        if(body.has("phone"))entity.phone=DriverValidation.optionalText(body,"phone",50);
        if(body.has("email"))entity.email=DriverValidation.optionalText(body,"email",254);
        if(body.has("dutyStatus"))entity.dutyStatus=DriverValidation.dutyStatus(body);
        if(body.has("licenseReference"))entity.licenseReference=DriverValidation.optionalText(body,"licenseReference",100);
        if(body.has("licenseDueDate"))entity.licenseDueDate=DriverValidation.date(body,"licenseDueDate");
        // Every acknowledged PATCH advances version, including no-op requests under a fixed Clock.
        var now=clock.instant();var next=entity.updatedAt.plusNanos(1_000);
        entity.updatedAt=now.isAfter(next)?now:next;entity.updatedBy=actor.id();drivers.flush();return dto(entity);
    }
    /** Lock-capable read with no business mutation: canonical -> fresh DRIVER actor -> current binding/source. */
    @Transactional
    public DriverDtos.DriverSelf me(SessionPrincipal principal) {
        var actor=authorization.requireDriverSelfWithLocks(principal);
        var entity=drivers.findByAccountId(actor.id()).orElseThrow(DriverService::missing);
        entityManager.refresh(entity,LockModeType.PESSIMISTIC_WRITE);
        if(!actor.id().equals(entity.accountId))throw missing();
        var type=license(entity.licenseType);
        return new DriverDtos.DriverSelf(entity.id,entity.driverCode,entity.name,entity.phone,entity.email,entity.employmentStatus,
                entity.dutyStatus,entity.licenseType,type.code(),type.label(),type.enabled(),entity.licenseDueDate);
    }
    @Override public Optional<Snapshot> findCurrent(UUID driverId) {
        return driverId==null?Optional.empty():drivers.findById(driverId).map(this::snapshot);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Snapshot requireAdministrativelyAvailable(UUID driverId) {
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Driver source locks require a writable caller transaction");
        var entity=driver(driverId,true);
        if(entity.employmentStatus!=EmploymentStatus.ACTIVE||entity.dutyStatus!=DutyStatus.ON_DUTY)
            throw UpmsException.field("driverId","司機行政來源須同時為 ACTIVE 與 ON_DUTY");
        return snapshot(entity);
    }
    private void bind(UUID next,UUID current,UUID driverId) {
        // Same retained binding may be maintained even when its current account is no longer eligible.
        if(next==null||next.equals(current))return;
        accounts.requireEligibleForBinding(next);
        if(driverId==null?drivers.existsByAccountId(next):drivers.existsByAccountIdAndIdNot(next,driverId))
            throw new UpmsException(409,"此帳號已綁定其他司機",Map.of("accountId","請選擇尚未綁定的帳號，或先由管理者解除原綁定"));
    }
    private DriverEntity driver(UUID id,boolean write) {
        var entity=id==null?null:drivers.findById(id).orElse(null);if(entity==null)throw missing();
        if(write)entityManager.refresh(entity,LockModeType.PESSIMISTIC_WRITE);return entity;
    }
    private record License(String code,String label,boolean enabled) {}
    private License license(UUID typeId) {
        if(typeId==null)return new License(null,null,false);
        var type=dictionaries.findItem(LICENSE_TYPE,typeId).orElseThrow(()->new UpmsException(500,"駕照類別資料暫時無法讀取，請由管理員複核"));
        return new License(type.code(),type.label(),type.enabled());
    }
    private void activeLicense(UUID typeId) {
        try{dictionaries.requireEnabledItemForNewReference(LICENSE_TYPE,typeId);}
        catch(UpmsException failure) {
            if(failure.status()==400||failure.status()==404)throw UpmsException.field("licenseType","請選擇有效的駕照類別；項目或分類可能已停用");throw failure;
        }
    }
    private DriverAccountQueries.Summary account(UUID accountId) {
        if(accountId==null)return null;
        return accounts.findCurrent(accountId).orElseThrow(()->new UpmsException(500,"綁定帳號資料暫時無法讀取，請由管理員複核"));
    }
    private DriverDtos.Driver dto(DriverEntity entity) {
        var type=license(entity.licenseType);var account=account(entity.accountId);
        return new DriverDtos.Driver(entity.id,entity.driverCode,entity.name,entity.phone,entity.email,entity.accountId,
                account==null?null:account.username(),account==null?null:account.displayName(),account!=null&&account.bindingValid(),
                entity.employmentStatus,entity.dutyStatus,entity.licenseType,type.code(),type.label(),type.enabled(),entity.licenseReference,
                entity.licenseDueDate,entity.version,entity.createdAt,entity.createdBy,entity.updatedAt,entity.updatedBy);
    }
    private DriverDtos.DriverSummary summary(DriverEntity entity) {
        var type=license(entity.licenseType);var account=account(entity.accountId);
        return new DriverDtos.DriverSummary(entity.id,entity.driverCode,entity.name,entity.phone,entity.email,entity.accountId,
                account==null?null:account.username(),account==null?null:account.displayName(),account!=null&&account.bindingValid(),
                entity.employmentStatus,entity.dutyStatus,entity.licenseType,type.code(),type.label(),type.enabled(),entity.licenseDueDate,entity.version);
    }
    private Snapshot snapshot(DriverEntity entity) {
        var type=license(entity.licenseType);
        return new Snapshot(entity.id,entity.driverCode,entity.name,entity.phone,entity.email,entity.employmentStatus,entity.dutyStatus,
                entity.licenseType,type.code(),type.label(),type.enabled(),entity.licenseDueDate,entity.version);
    }
    private static UpmsException missing(){return new UpmsException(404,"找不到司機主檔或目前本人綁定");}
    private static UpmsException duplicateCode(){return new UpmsException(409,"司機編號已存在，包含停用及離職資料",Map.of("driverCode","請使用尚未建立的司機編號"));}
    private static String literal(String value){return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    private static Specification<DriverEntity> filter(DriverPageQuery query,boolean options) {
        return(root,q,cb)->{
            var predicates=new ArrayList<Predicate>();
            if(query.licenseType()!=null)predicates.add(cb.equal(root.get("licenseType"),query.licenseType()));
            if(options)predicates.add(cb.and(cb.equal(root.get("employmentStatus"),EmploymentStatus.ACTIVE),cb.equal(root.get("dutyStatus"),DutyStatus.ON_DUTY)));
            else {
                if(query.employmentStatus()!=null)predicates.add(cb.equal(root.get("employmentStatus"),query.employmentStatus()));
                if(query.dutyStatus()!=null)predicates.add(cb.equal(root.get("dutyStatus"),query.dutyStatus()));
            }
            if(!query.q().isEmpty()) {
                String code=Normalizer.normalize(query.q().toUpperCase(Locale.ROOT),Normalizer.Form.NFC);
                var matches=new ArrayList<Predicate>();matches.add(cb.like(root.get("driverCode"),"%"+literal(code)+"%",'\\'));
                String pattern="%"+literal(query.q())+"%";
                for(String field:List.of("name","phone","email"))matches.add(cb.like(cb.lower(root.get(field)),cb.lower(cb.literal(pattern)),'\\'));
                predicates.add(cb.or(matches.toArray(Predicate[]::new)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
