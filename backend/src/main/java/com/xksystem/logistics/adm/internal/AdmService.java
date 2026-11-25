package com.xksystem.logistics.adm.internal;

import com.xksystem.logistics.adm.*;
import com.xksystem.logistics.upms.*;
import com.xksystem.logistics.upms.security.SessionPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly=true)
public class AdmService implements AdmQueries {
    private final CategoryRepository categories;
    private final ItemRepository items;
    private final DefinitionRepository definitions;
    private final ParameterRepository parameters;
    private final ChangeLogRepository logs;
    private final UpmsAuthorization authorization;
    private final Clock clock;
    private final JsonMapper json;
    private static final Pattern RESERVED=Pattern.compile("(^|_)(AUTH|SESSION|SECURITY|SECRET|PASSWORD|TOKEN|CSRF|JWT|COOKIE|TLS|SSL|DATABASE|DATASOURCE|SMTP|CREDENTIAL|INFRA|PORT|HOST|URL|URI|ORIGIN|CORS|ITERATIONS|LOGIN|PBKDF2|RATE_LIMIT)(_|$)");
    private static final Pattern CREDENTIAL=Pattern.compile("(?is)-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----|\\bBearer\\s+[a-zA-Z0-9._~+/-]{12,}|\\b(?:password|secret|token|api[_-]?key|XKSESSION|JSESSIONID)\\s*[:=]\\s*\\S+|\\b(?:jdbc:|postgres(?:ql)?://|mysql://|redis://|mongodb(?:\\+srv)?://)|\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\\b|https?://[^\\s/@]+:[^\\s/@]+@");

    public AdmService(CategoryRepository categories,ItemRepository items,DefinitionRepository definitions,
                      ParameterRepository parameters,ChangeLogRepository logs,UpmsAuthorization authorization,Clock clock,JsonMapper json) {
        this.categories=categories;this.items=items;this.definitions=definitions;this.parameters=parameters;
        this.logs=logs;this.authorization=authorization;this.clock=clock;this.json=json;
    }

    public AdmDtos.PageData<AdmDtos.Category> categories(SessionPrincipal actor,AdmPageQuery query) {
        authorization.requireAdmin(actor);
        return AdmDtos.PageData.from(categories.findAll(filter(query,"code","name"),query.pageable()).map(CategoryEntity::dto));
    }
    public AdmDtos.PageData<AdmDtos.Item> items(SessionPrincipal actor,UUID categoryId,AdmPageQuery query) {
        authorization.requireAdmin(actor); category(categoryId);
        Specification<ItemEntity> parent=(root,q,cb)->cb.equal(root.get("categoryId"),categoryId);
        return AdmDtos.PageData.from(items.findAll(parent.and(filter(query,"code","label")),query.pageable()).map(ItemEntity::dto));
    }
    public AdmDtos.PageData<AdmDtos.Parameter> parameters(SessionPrincipal actor,AdmPageQuery query) {
        authorization.requireAdmin(actor);
        return AdmDtos.PageData.from(parameters.findAll(filter(query,"key","name"),query.pageable()).map(ParameterEntity::dto));
    }
    public List<AdmDtos.Definition> definitions(SessionPrincipal actor) {
        authorization.requireAdmin(actor);
        return definitions.findAllByOrderByKeyAsc().stream().peek(this::validateDefinition).map(DefinitionEntity::dto).toList();
    }
    public AdmDtos.PageData<AdmDtos.ChangeLog> logs(SessionPrincipal actor,AdmPageQuery query) {
        authorization.requireAdmin(actor);
        Specification<ChangeLogEntity> specification=(root,q,cb)-> {
            var predicates=new ArrayList<Predicate>();
            if(query.targetType()!=null) predicates.add(cb.equal(root.get("targetType"),query.targetType()));
            if(query.targetId()!=null) predicates.add(cb.equal(root.get("targetId"),query.targetId()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return AdmDtos.PageData.from(logs.findAll(specification,query.pageable()).map(ChangeLogEntity::dto));
    }
    public List<AdmDtos.Item> options(SessionPrincipal actor,String code) {
        authorization.requireAdmin(actor); return enabledOptions(code);
    }
    @Override public List<AdmDtos.Item> enabledOptions(String code) {
        var parent=categories.findByCode(AdmValidation.code(code,"categoryCode")).orElseThrow(()->missing("分類"));
        if(!parent.enabled) return List.of();
        return items.enabledOptions(parent.id).stream().map(ItemEntity::dto).toList();
    }
    @Override public Optional<String> historicalLabel(String categoryCode,String itemCode) {
        return categories.findByCode(AdmValidation.code(categoryCode,"categoryCode"))
                .flatMap(parent->items.findByCategoryIdAndCode(parent.id,AdmValidation.code(itemCode,"itemCode"))).map(item->item.label);
    }
    @Override public Optional<String> historicalLabel(UUID itemId) { return items.findById(itemId).map(item->item.label); }
    @Override public Optional<AdmDtos.Parameter> enabledParameter(String key) {
        return parameters.findByKeyAndEnabledTrue(AdmValidation.code(key,"key")).map(parameter -> {
            var definition=definition(parameter.key);
            if(parameter.valueType!=definition.valueType) throw UpmsException.field("valueType","現有參數不符合核准定義");
            String canonical=parameterValue(definition,parameter.valueText);
            if(!canonical.equals(parameter.valueText)) throw UpmsException.field("valueText","現有參數值須由管理員複核");
            return parameter.dto();
        });
    }

    @Transactional
    public AdmDtos.Category createCategory(SessionPrincipal principal,String code,String name,boolean enabled,int sortOrder) {
        var actor=authorization.requireAdminForWrite(principal);
        String canonical=AdmValidation.code(code,"code");
        if(categories.findByCode(canonical).isPresent()) throw duplicate();
        var entity=new CategoryEntity();entity.id=UUID.randomUUID();entity.code=canonical;
        entity.name=label(name,"name");entity.enabled=enabled;entity.sortOrder=sort(sortOrder);
        entity.createdAt=clock.instant();entity.updatedAt=clock.instant();
        entity=categories.saveAndFlush(entity);
        audit("CATEGORY",entity.id,"CREATE",actor,null,entity.dto());return entity.dto();
    }
    @Transactional
    public AdmDtos.Category updateCategory(SessionPrincipal principal,UUID id,String name,boolean enabled,int sortOrder,long version) {
        var actor=authorization.requireAdminForWrite(principal);
        var entity=categories.locked(id).orElseThrow(()->missing("分類"));
        mutable(entity.readOnly);version(entity.version,version);var before=entity.dto();
        entity.name=label(name,"name");entity.enabled=enabled;entity.sortOrder=sort(sortOrder);entity.updatedAt=clock.instant();
        categories.flush();audit("CATEGORY",id,"UPDATE",actor,before,entity.dto());return entity.dto();
    }
    @Transactional
    public AdmDtos.Item createItem(SessionPrincipal principal,UUID parentId,String code,String label,boolean enabled,int sortOrder) {
        var actor=authorization.requireAdminForWrite(principal);
        var parent=categories.locked(parentId).orElseThrow(()->missing("分類"));mutable(parent.readOnly);
        String canonical=AdmValidation.code(code,"code");
        if(items.findByCategoryIdAndCode(parentId,canonical).isPresent()) throw duplicate();
        var entity=new ItemEntity();entity.id=UUID.randomUUID();entity.categoryId=parentId;entity.code=canonical;
        entity.label=label(label,"label");entity.enabled=enabled;entity.sortOrder=sort(sortOrder);
        entity.createdAt=clock.instant();entity.updatedAt=clock.instant();entity=items.saveAndFlush(entity);
        audit("ITEM",entity.id,"CREATE",actor,null,entity.dto());return entity.dto();
    }
    @Transactional
    public AdmDtos.Item updateItem(SessionPrincipal principal,UUID id,String label,boolean enabled,int sortOrder,long version) {
        var actor=authorization.requireAdminForWrite(principal);
        var entity=items.locked(id).orElseThrow(()->missing("項目"));
        var parent=category(entity.categoryId);mutable(parent.readOnly||entity.readOnly);version(entity.version,version);var before=entity.dto();
        entity.label=label(label,"label");entity.enabled=enabled;entity.sortOrder=sort(sortOrder);entity.updatedAt=clock.instant();
        items.flush();audit("ITEM",id,"UPDATE",actor,before,entity.dto());return entity.dto();
    }
    @Transactional
    public AdmDtos.Parameter createParameter(SessionPrincipal principal,String key,String name,ParameterType type,String value,String description,boolean enabled) {
        var actor=authorization.requireAdminForWrite(principal);
        var definition=definition(key);
        if(type!=definition.valueType) throw UpmsException.field("valueType","型別須符合核准定義");
        if(parameters.findByKey(key).isPresent()) throw duplicate();
        var entity=new ParameterEntity();entity.id=UUID.randomUUID();entity.key=key;entity.valueType=type;
        entity.name=label(name,"name");entity.valueText=parameterValue(definition,value);
        entity.description=description(description);entity.enabled=enabled;entity.createdAt=clock.instant();entity.updatedAt=clock.instant();
        entity=parameters.saveAndFlush(entity);audit("PARAMETER",entity.id,"CREATE",actor,null,entity.dto());return entity.dto();
    }
    @Transactional
    public AdmDtos.Parameter updateParameter(SessionPrincipal principal,UUID id,String name,String value,String description,boolean enabled,long version) {
        var actor=authorization.requireAdminForWrite(principal);
        var entity=parameters.locked(id).orElseThrow(()->missing("參數"));version(entity.version,version);var before=entity.dto();
        var definition=definition(entity.key);
        if(entity.valueType!=definition.valueType) throw UpmsException.field("valueType","參數型別與核准定義不符");
        entity.name=label(name,"name");entity.valueText=parameterValue(definition,value);
        entity.description=description(description);entity.enabled=enabled;entity.updatedAt=clock.instant();
        parameters.flush();audit("PARAMETER",id,"UPDATE",actor,before,entity.dto());return entity.dto();
    }

    private CategoryEntity category(UUID id){return categories.findById(id).orElseThrow(()->missing("分類"));}
    private DefinitionEntity definition(String key) {
        AdmValidation.code(key,"key");
        if(RESERVED.matcher(key).find()) throw UpmsException.field("key","不可使用認證、安全或基礎設施設定鍵");
        var definition=definitions.findById(key).orElseThrow(()->UpmsException.field("key","此業務參數鍵尚未核准"));
        validateDefinition(definition);
        return definition;
    }
    private void validateDefinition(DefinitionEntity definition) {
        try {
            AdmValidation.code(definition.key,"key");
            if(RESERVED.matcher(definition.key).find()||definition.valueType==null) throw new IllegalArgumentException();
            if(!label(definition.name,"name").equals(definition.name)||definition.description==null
                    ||definition.description.isBlank()||!description(definition.description).equals(definition.description)) throw new IllegalArgumentException();
            if(definition.allowedValues==null||definition.allowedValues.size()>1000) throw new IllegalArgumentException();
            var unique=new HashSet<String>();
            for(Object raw:definition.allowedValues) {
                if(!(raw instanceof String text)||!AdmValidation.text(text,"allowedValues",0,2000,false,true).equals(text)
                        ||CREDENTIAL.matcher(text).find()||!unique.add(text)) throw new IllegalArgumentException();
            }
            boolean valid=switch(definition.valueType) {
                case TEXT -> definition.minValue==null&&definition.maxValue==null&&definition.decimalScale==null
                        &&(!unique.isEmpty() ? definition.minLength==null&&definition.maxLength==null
                        : definition.minLength!=null&&definition.maxLength!=null&&definition.minLength>=0
                        &&definition.maxLength<=2000&&definition.minLength<=definition.maxLength);
                case NUMBER -> unique.isEmpty()&&definition.minLength==null&&definition.maxLength==null
                        &&definition.minValue!=null&&definition.maxValue!=null&&definition.minValue.compareTo(definition.maxValue)<=0
                        &&definition.decimalScale!=null&&definition.decimalScale>=0&&definition.decimalScale<=12;
                case BOOLEAN -> unique.isEmpty()&&definition.minLength==null&&definition.maxLength==null
                        &&definition.minValue==null&&definition.maxValue==null&&definition.decimalScale==null;
            };
            if(!valid) throw new IllegalArgumentException();
        } catch(IllegalArgumentException|UpmsException invalid) {
            throw UpmsException.field("key","核准定義不完整或不安全，請由負責人複核");
        }
    }
    private String parameterValue(DefinitionEntity definition,String value) {
        String normalized=AdmValidation.text(value,"valueText",0,2000,false,true);
        if(CREDENTIAL.matcher(normalized).find()) throw UpmsException.field("valueText","參數不可保存憑證、金鑰或連線秘密");
        boolean valid=false;
        switch(definition.valueType) {
            case TEXT -> {
                if(definition.allowedValues!=null&&!definition.allowedValues.isEmpty()) {
                    valid=definition.minLength==null&&definition.maxLength==null
                            &&definition.allowedValues.contains(normalized);
                } else if(definition.minLength!=null&&definition.maxLength!=null
                        &&definition.minLength>=0&&definition.maxLength<=2000&&definition.minLength<=definition.maxLength) {
                    int count=normalized.codePointCount(0,normalized.length());valid=count>=definition.minLength&&count<=definition.maxLength;
                }
                valid=valid&&definition.minValue==null&&definition.maxValue==null&&definition.decimalScale==null;
            }
            case NUMBER -> {
                if(definition.minValue!=null&&definition.maxValue!=null&&definition.minValue.compareTo(definition.maxValue)<=0
                        &&definition.decimalScale!=null&&definition.decimalScale>=0&&definition.decimalScale<=12
                        &&definition.minLength==null&&definition.maxLength==null
                        &&(definition.allowedValues==null||definition.allowedValues.isEmpty())
                        &&normalized.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
                    var numeric=new BigDecimal(normalized);
                    valid=numeric.scale()<=definition.decimalScale&&numeric.compareTo(definition.minValue)>=0&&numeric.compareTo(definition.maxValue)<=0;
                    if(valid) normalized=numeric.stripTrailingZeros().toPlainString();
                }
            }
            case BOOLEAN -> valid=Set.of("true","false").contains(normalized)&&definition.minLength==null&&definition.maxLength==null
                    &&definition.minValue==null&&definition.maxValue==null&&definition.decimalScale==null
                    &&(definition.allowedValues==null||definition.allowedValues.isEmpty());
        }
        if(!valid) throw UpmsException.field("valueText","值或核准定義不符合型別與範圍限制");
        return normalized;
    }
    private static String label(String value,String field){return AdmValidation.text(value,field,1,80,true,false);}
    private static String description(String value){return AdmValidation.text(value,"description",0,500,true,true);}
    private static int sort(int value){if(value<0||value>1_000_000)throw UpmsException.field("sortOrder","排序超出允許範圍");return value;}
    private static void mutable(boolean readOnly){if(readOnly)throw UpmsException.field("readOnly","固定枚舉對照不可編輯或新增項目");}
    private static void version(long actual,long expected){if(expected<0)throw UpmsException.field("version","請提供有效版本");if(actual!=expected)throw new UpmsException(409,"資料已由其他操作更新，請重新讀取後再試");}
    private static UpmsException duplicate(){return new UpmsException(409,"代碼或參數鍵已存在，請選擇其他值");}
    private static UpmsException missing(String kind){return new UpmsException(404,"找不到"+kind);}
    @SuppressWarnings("unchecked")
    private Map<String,Object> snapshot(Object dto){return dto==null?null:json.convertValue(dto,Map.class);}
    private void audit(String type,UUID id,String action,AccountDto actor,Object before,Object after) {
        var log=new ChangeLogEntity();log.id=UUID.randomUUID();log.targetType=type;log.targetId=id;log.action=action;
        log.actorId=actor.id();log.actorUsername=actor.username();log.occurredAt=clock.instant();log.beforeValue=snapshot(before);log.afterValue=snapshot(after);
        logs.saveAndFlush(log);
    }
    private static <T> Specification<T> filter(AdmPageQuery query,String code,String label) {
        return (root,q,cb)-> {
            var predicates=new ArrayList<Predicate>();
            if(query.enabled()!=null) predicates.add(cb.equal(root.get("enabled"),query.enabled()));
            if(!query.q().isEmpty()) {
                String literal=query.q().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
                predicates.add(cb.or(cb.like(cb.lower(root.get(code)),"%"+literal+"%",'\\'),cb.like(cb.lower(root.get(label)),"%"+literal+"%",'\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
