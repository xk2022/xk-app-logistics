package com.xksystem.logistics.fms.internal;

import com.xksystem.logistics.adm.AdmQueries;
import com.xksystem.logistics.fms.*;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

@Service @Transactional(readOnly=true)
public class VehicleService implements VehicleQueries {
    private static final String VEHICLE_TYPE="VEHICLE_TYPE";
    private final VehicleRepository vehicles;
    private final AdmQueries dictionaries;
    private final UpmsAuthorization authorization;
    private final Clock clock;
    @PersistenceContext private EntityManager entityManager;
    public VehicleService(VehicleRepository vehicles,AdmQueries dictionaries,UpmsAuthorization authorization,Clock clock) {
        this.vehicles=vehicles;this.dictionaries=dictionaries;this.authorization=authorization;this.clock=clock;
    }
    public VehicleDtos.PageData<VehicleDtos.Vehicle> vehicles(SessionPrincipal principal,VehiclePageQuery query) {
        authorization.requireVehicleManager(principal);
        return VehicleDtos.PageData.from(vehicles.findAll(filter(query,false),query.pageable()).map(this::dto));
    }
    public VehicleDtos.Vehicle vehicle(SessionPrincipal principal,UUID id) {
        authorization.requireVehicleManager(principal);return dto(vehicle(id,false));
    }
    public List<VehicleDtos.VehicleType> types(SessionPrincipal principal) {
        authorization.requireVehicleManager(principal);
        return dictionaries.enabledOptions(VEHICLE_TYPE).stream().map(item->new VehicleDtos.VehicleType(item.id(),item.code(),item.label())).toList();
    }
    public VehicleDtos.PageData<VehicleDtos.VehicleOption> options(SessionPrincipal principal,VehiclePageQuery query) {
        authorization.requireVehicleManager(principal);
        return VehicleDtos.PageData.from(vehicles.findAll(filter(query,true),query.pageable()).map(entity->{
            var type=historicalType(entity.vehicleType);
            return new VehicleDtos.VehicleOption(entity.id,entity.plateNo,entity.vehicleType,type.code(),type.label(),type.enabled(),capacity(entity),entity.version);
        }));
    }
    @Transactional
    public VehicleDtos.Vehicle create(SessionPrincipal principal,JsonNode body) {
        var actor=authorization.requireVehicleManagerForWrite(principal);VehicleValidation.fields(body,false);
        String plate=VehicleValidation.plateNo(body);if(vehicles.existsByPlateNo(plate))throw duplicate();
        var type=activeType(VehicleValidation.vehicleType(body));
        var entity=new VehicleEntity();entity.id=UUID.randomUUID();entity.plateNo=plate;entity.vehicleType=type.id();
        entity.capacityTon=VehicleValidation.capacityTon(body);entity.registrationNo=VehicleValidation.optionalText(body,"registrationNo",100);
        entity.registrationDate=VehicleValidation.date(body,"registrationDate");entity.inspectionDueDate=VehicleValidation.date(body,"inspectionDueDate");
        entity.administrativeStatus=body.has("administrativeStatus")?VehicleValidation.status(body):AdministrativeStatus.AVAILABLE;
        entity.createdAt=clock.instant();entity.updatedAt=entity.createdAt;entity.createdBy=actor.id();entity.updatedBy=actor.id();
        return dto(vehicles.saveAndFlush(entity));
    }
    @Transactional
    public VehicleDtos.Vehicle update(SessionPrincipal principal,UUID id,JsonNode body) {
        var actor=authorization.requireVehicleManagerForWrite(principal);VehicleValidation.fields(body,true);
        var entity=vehicle(id,true);long expected=VehicleValidation.version(body);
        if(entity.version!=expected)throw new UpmsException(409,"資料已由其他操作更新，請重新讀取後再試",Map.of("version","版本已更新，請重新讀取後再試"));
        if(entity.version>=VehicleValidation.MAX_VERSION)throw new UpmsException(409,"資料版本已達上限，請由管理員處理",Map.of("version","版本已達上限，請由管理員處理"));
        if(body.has("plateNo")) {
            String plate=VehicleValidation.plateNo(body);
            if(vehicles.existsByPlateNoAndIdNot(plate,id))throw duplicate();entity.plateNo=plate;
        }
        if(body.has("vehicleType")) {
            UUID next=VehicleValidation.vehicleType(body);
            if(!entity.vehicleType.equals(next))entity.vehicleType=activeType(next).id();
        }
        if(body.has("capacityTon"))entity.capacityTon=VehicleValidation.capacityTon(body);
        if(body.has("registrationNo"))entity.registrationNo=VehicleValidation.optionalText(body,"registrationNo",100);
        if(body.has("registrationDate"))entity.registrationDate=VehicleValidation.date(body,"registrationDate");
        if(body.has("inspectionDueDate"))entity.inspectionDueDate=VehicleValidation.date(body,"inspectionDueDate");
        if(body.has("administrativeStatus"))entity.administrativeStatus=VehicleValidation.status(body);
        // Every acknowledged PATCH advances the version, even under a fixed clock
        // or when only decimal scale changes (BigDecimal dirty checking uses compareTo).
        // The ordinary full-row update then also persists the exact requested scale.
        var now=clock.instant();var next=entity.updatedAt.plusNanos(1_000);
        entity.updatedAt=now.isAfter(next)?now:next;entity.updatedBy=actor.id();vehicles.flush();return dto(entity);
    }
    @Override public Optional<Snapshot> findCurrent(UUID vehicleId) {
        return vehicleId==null?Optional.empty():vehicles.findById(vehicleId).map(this::snapshot);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Snapshot requireAdministrativelyAvailable(UUID vehicleId) {
        if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalTransactionStateException("Vehicle source locks require a writable caller transaction");
        var entity=vehicle(vehicleId,true);
        if(entity.administrativeStatus!=AdministrativeStatus.AVAILABLE)
            throw UpmsException.field("vehicleId","車輛管理狀態不是 AVAILABLE");
        return snapshot(entity);
    }
    private VehicleEntity vehicle(UUID id,boolean write) {
        var entity=id==null?null:vehicles.findById(id).orElse(null);
        if(entity==null)throw new UpmsException(404,"找不到車輛");
        // Refresh acquires the lock atomically even when a trusted caller cached an old @Version.
        if(write)entityManager.refresh(entity,LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }
    private AdmQueries.ItemSnapshot activeType(UUID typeId) {
        try{return dictionaries.requireEnabledItemForNewReference(VEHICLE_TYPE,typeId);}
        catch(UpmsException failure) {
            if(failure.status()==400||failure.status()==404)throw UpmsException.field("vehicleType","請選擇有效的車種；車種或分類可能已停用");
            throw failure;
        }
    }
    private AdmQueries.ItemSnapshot historicalType(UUID typeId) {
        return dictionaries.findItem(VEHICLE_TYPE,typeId).orElseThrow(()->new UpmsException(500,"車種資料暫時無法讀取，請由管理員複核"));
    }
    private String capacity(VehicleEntity entity){return entity.capacityTon==null?null:entity.capacityTon.toPlainString();}
    private Snapshot snapshot(VehicleEntity entity) {
        var type=historicalType(entity.vehicleType);
        return new Snapshot(entity.id,entity.plateNo,entity.vehicleType,type.code(),type.label(),type.enabled(),capacity(entity),entity.administrativeStatus,entity.version);
    }
    private VehicleDtos.Vehicle dto(VehicleEntity entity) {
        var type=historicalType(entity.vehicleType);
        return new VehicleDtos.Vehicle(entity.id,entity.plateNo,entity.vehicleType,type.code(),type.label(),type.enabled(),capacity(entity),
                entity.registrationNo,entity.registrationDate,entity.inspectionDueDate,entity.administrativeStatus,entity.version,
                entity.createdAt,entity.createdBy,entity.updatedAt,entity.updatedBy);
    }
    private static UpmsException duplicate(){return new UpmsException(409,"車牌已存在，包含已停用的車輛",Map.of("plateNo","車牌已存在，包含已停用的車輛"));}
    private static Specification<VehicleEntity> filter(VehiclePageQuery query,boolean options) {
        return(root,q,cb)->{
            var predicates=new ArrayList<Predicate>();
            if(query.vehicleType()!=null)predicates.add(cb.equal(root.get("vehicleType"),query.vehicleType()));
            if(options)predicates.add(cb.equal(root.get("administrativeStatus"),AdministrativeStatus.AVAILABLE));
            else if(query.administrativeStatus()!=null)predicates.add(cb.equal(root.get("administrativeStatus"),query.administrativeStatus()));
            if(!query.q().isEmpty()) {
                // Plate input and search share ROOT uppercase expansion (e.g. ß -> SS).
                // Registration uses the database's case mapping on both operands.
                String plate=Normalizer.normalize(query.q().toUpperCase(Locale.ROOT),Normalizer.Form.NFC);
                predicates.add(cb.or(cb.like(root.get("plateNo"),"%"+literal(plate)+"%",'\\'),
                        cb.like(cb.lower(root.get("registrationNo")),cb.lower(cb.literal("%"+literal(query.q())+"%")),'\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
    private static String literal(String value){return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
}
