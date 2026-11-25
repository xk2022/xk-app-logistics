package com.xksystem.logistics.fms;

import com.xksystem.logistics.upms.UpmsException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Driver-specific shapes reuse the already established FMS text/date/integer primitives. */
public final class DriverValidation {
    private DriverValidation() {}
    public static final long MAX_VERSION=VehicleValidation.MAX_VERSION;
    private static final Set<String> MUTABLE=Set.of("name","phone","email","accountId","employmentStatus","dutyStatus","licenseType","licenseReference","licenseDueDate");
    public static JsonNode parse(byte[] bytes){return VehicleValidation.parse(bytes);}
    public static void fields(JsonNode body,boolean patch) {
        if(body==null||!body.isObject())throw UpmsException.field("request","請提供 JSON 物件");
        for(String field:body.propertyNames()) {
            if(!MUTABLE.contains(field)&&!(patch?field.equals("version"):field.equals("driverCode")))
                throw UpmsException.field(field,"此欄位不可設定或變更");
        }
        for(String field:patch?Set.of("version"):Set.of("driverCode","name"))
            if(!body.has(field)||body.get(field).isNull())throw UpmsException.field(field,"此欄位為必填");
        if(patch&&body.size()<2)throw UpmsException.field("request","請提供至少一個要更新的欄位");
    }
    public static String driverCode(JsonNode body){return driverCode(VehicleValidation.string(body,"driverCode"));}
    public static String driverCode(String raw) {
        try{return VehicleValidation.plateNo(raw);}
        catch(UpmsException failure){throw UpmsException.field("driverCode","司機編號正規化後須為 1 至 40 個有效字元");}
    }
    public static String name(JsonNode body) {
        String value=text(VehicleValidation.string(body,"name"),"name",200);
        if(value==null)throw UpmsException.field("name","請提供 1 至 200 個字元的姓名");return value;
    }
    public static String text(String raw,String field,int maximum){return VehicleValidation.text(raw,field,maximum);}
    public static String optionalText(JsonNode body,String field,int maximum){return VehicleValidation.optionalText(body,field,maximum);}
    public static UUID uuid(String raw,String field){return VehicleValidation.uuid(raw,field);}
    public static UUID optionalUuid(JsonNode body,String field) {
        var value=body.get(field);return value==null||value.isNull()?null:uuid(VehicleValidation.string(body,field),field);
    }
    public static LocalDate date(JsonNode body,String field){return VehicleValidation.date(body,field);}
    public static long version(JsonNode body){return VehicleValidation.version(body);}
    public static EmploymentStatus employmentStatus(JsonNode body){return employmentStatus(VehicleValidation.string(body,"employmentStatus"));}
    public static EmploymentStatus employmentStatus(String raw) {
        if(raw==null)throw UpmsException.field("employmentStatus","請提供合法在職狀態");
        try{return EmploymentStatus.valueOf(raw);}
        catch(IllegalArgumentException failure){throw UpmsException.field("employmentStatus","在職狀態僅支援 ACTIVE、INACTIVE 或 LEFT");}
    }
    public static DutyStatus dutyStatus(JsonNode body){return dutyStatus(VehicleValidation.string(body,"dutyStatus"));}
    public static DutyStatus dutyStatus(String raw) {
        if(raw==null)throw UpmsException.field("dutyStatus","請提供合法出勤狀態");
        try{return DutyStatus.valueOf(raw);}
        catch(IllegalArgumentException failure){throw UpmsException.field("dutyStatus","出勤狀態僅支援 ON_DUTY 或 OFF_DUTY");}
    }
}
