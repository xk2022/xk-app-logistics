package com.xksystem.logistics.fms;

import com.xksystem.logistics.upms.UpmsException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** FMS-only wire validation; never changes existing module parsers or contract normalization. */
public final class VehicleValidation {
    private VehicleValidation() {}
    public static final long MAX_VERSION=9_007_199_254_740_991L;
    private static final Pattern UUID_TEXT=Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DECIMAL=Pattern.compile("(0|[1-9][0-9]*)(\\.[0-9]+)?");
    private static final Pattern DATE=Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Set<String> MUTABLE=Set.of("plateNo","vehicleType","capacityTon","registrationNo","registrationDate","inspectionDueDate","administrativeStatus");
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
                    DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public static JsonNode parse(byte[] bytes) {
        try {
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var node=JSON.readTree(raw);
            if(node==null)throw UpmsException.field("request","請提供 JSON 物件");
            return node;
        }catch(CharacterCodingException|RuntimeException failure){throw UpmsException.field("request","JSON 格式不合法或有重複欄位");}
    }
    public static void fields(JsonNode body,boolean patch) {
        if(body==null||!body.isObject())throw UpmsException.field("request","請提供 JSON 物件");
        for(String field:body.propertyNames())if(!MUTABLE.contains(field)&&!(patch&&field.equals("version")))
            throw UpmsException.field(field,"此欄位不可設定或變更");
        for(String field:patch?Set.of("version"):Set.of("plateNo","vehicleType"))
            if(!body.has(field)||body.get(field).isNull())throw UpmsException.field(field,"此欄位為必填");
        if(patch&&body.size()<2)throw UpmsException.field("request","請提供至少一個要更新的欄位");
    }
    public static String string(JsonNode body,String field) {
        var value=body.get(field);
        if(value==null||!value.isString())throw UpmsException.field(field,"請提供字串");
        return value.asString();
    }
    public static String plateNo(JsonNode body) {return plateNo(string(body,"plateNo"));}
    public static String plateNo(String raw) {
        String value=normalized(raw,"plateNo").toUpperCase(Locale.ROOT);
        value=Normalizer.normalize(value,Normalizer.Form.NFC);
        validateText(value,"plateNo",1,40);
        return value;
    }
    public static String optionalText(JsonNode body,String field,int maximum) {
        var value=body.get(field);
        return value==null||value.isNull()?null:text(string(body,field),field,maximum);
    }
    public static String text(String raw,String field,int maximum) {
        String value=normalized(raw,field);validateText(value,field,0,maximum);
        return value.isEmpty()?null:value;
    }
    private static String normalized(String raw,String field) {
        if(raw==null)throw UpmsException.field(field,"請提供字串");
        for(int i=0;i<raw.length();i++) {
            char ch=raw.charAt(i);
            if(Character.isHighSurrogate(ch)) {
                if(i+1>=raw.length()||!Character.isLowSurrogate(raw.charAt(++i)))throw UpmsException.field(field,"包含無效 Unicode 字元");
            }else if(Character.isLowSurrogate(ch))throw UpmsException.field(field,"包含無效 Unicode 字元");
        }
        String value=Normalizer.normalize(raw,Normalizer.Form.NFC);
        int start=0,end=value.length();
        while(start<end&&space(value.codePointAt(start)))start+=Character.charCount(value.codePointAt(start));
        while(end>start&&space(value.codePointBefore(end)))end-=Character.charCount(value.codePointBefore(end));
        return value.substring(start,end);
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xFEFF;}
    private static void validateText(String value,String field,int minimum,int maximum) {
        int size=value.codePointCount(0,value.length());
        if(size<minimum||size>maximum||value.codePoints().anyMatch(Character::isISOControl))
            throw UpmsException.field(field,"文字長度或內容不符合限制");
    }
    public static UUID uuid(String raw,String field) {
        if(raw==null||!UUID_TEXT.matcher(raw).matches())throw UpmsException.field(field,"請提供完整 UUID 格式");
        return UUID.fromString(raw);
    }
    public static UUID vehicleType(JsonNode body){return uuid(string(body,"vehicleType"),"vehicleType");}
    public static BigDecimal capacityTon(JsonNode body) {
        var value=body.get("capacityTon");if(value==null||value.isNull())return null;
        String raw=string(body,"capacityTon");
        if(raw.length()>32||!DECIMAL.matcher(raw).matches())throw UpmsException.field("capacityTon","請提供最多 32 字元的非負十進位字串或 null");
        return new BigDecimal(raw);
    }
    public static LocalDate date(JsonNode body,String field) {
        var value=body.get(field);if(value==null||value.isNull())return null;
        String raw=string(body,field);
        if(!DATE.matcher(raw).matches())throw UpmsException.field(field,"請提供 YYYY-MM-DD 日期或 null");
        try {
            LocalDate result=LocalDate.parse(raw);
            if(result.getYear()<1)throw new DateTimeException("Invalid year");
            return result;
        }catch(DateTimeException failure){throw UpmsException.field(field,"請提供 0001 至 9999 年的有效日曆日期");}
    }
    public static AdministrativeStatus status(String raw) {
        if(raw==null)throw UpmsException.field("administrativeStatus","請提供合法管理狀態");
        try{return AdministrativeStatus.valueOf(raw);}
        catch(IllegalArgumentException failure){throw UpmsException.field("administrativeStatus","管理狀態僅支援 AVAILABLE、MAINTENANCE 或 INACTIVE");}
    }
    public static AdministrativeStatus status(JsonNode body){return status(string(body,"administrativeStatus"));}
    public static long version(JsonNode body) {
        var value=body.get("version");
        if(value==null||!value.isNumber())throw UpmsException.field("version","請提供有效版本");
        try {
            BigDecimal version=value.decimalValue();
            if(version.signum()<0||version.compareTo(BigDecimal.valueOf(MAX_VERSION))>0)throw new ArithmeticException();
            return version.longValueExact();
        }catch(ArithmeticException failure){throw UpmsException.field("version","版本須為 0 至 9007199254740991 的整數");}
    }
}
