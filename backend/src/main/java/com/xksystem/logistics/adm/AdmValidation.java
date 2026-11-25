package com.xksystem.logistics.adm;

import com.xksystem.logistics.upms.UpmsException;
import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class AdmValidation {
    private AdmValidation() {}
    public static void fields(JsonNode body,String...allowed) {
        if(body==null||!body.isObject()) throw UpmsException.field("request","請提供 JSON 物件");
        Set<String> accepted=Set.of(allowed);
        for(String field:body.propertyNames()) if(!accepted.contains(field)) throw UpmsException.field(field,"此欄位不可設定或變更");
        for(String field:allowed) if(!body.has(field)||body.get(field).isNull()) throw UpmsException.field(field,"此欄位為必填");
    }
    public static String string(JsonNode body,String field) {
        JsonNode value=body.get(field);
        if(value==null||!value.isString()) throw UpmsException.field(field,"請提供字串");
        return value.asString();
    }
    public static boolean bool(JsonNode body,String field) {
        JsonNode value=body.get(field);
        if(value==null||!value.isBoolean()) throw UpmsException.field(field,"請提供布林值");
        return value.asBoolean();
    }
    public static long version(JsonNode body) {
        var value=body.get("version");
        if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0)
            throw UpmsException.field("version","請提供有效版本");
        return value.asLong();
    }
    public static int sortOrder(JsonNode body) {
        var value=body.get("sortOrder");
        if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<0||value.asInt()>1_000_000)
            throw UpmsException.field("sortOrder","排序須為 0 至 1000000 的整數");
        return value.asInt();
    }
    public static String code(String value,String field) {
        if(value==null||!value.matches("[A-Z][A-Z0-9_]{1,63}")) throw UpmsException.field(field,"代碼須為 2 至 64 個大寫英數字或底線，並以字母開頭");
        return value;
    }
    public static String text(String value,String field,int min,int max,boolean strip,boolean newline) {
        if(value==null||value.length()>max*4+8) throw UpmsException.field(field,"文字長度超出允許範圍");
        for(int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            if(Character.isHighSurrogate(ch)) {
                if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i))) throw UpmsException.field(field,"包含無效 Unicode 字元");
            } else if(Character.isLowSurrogate(ch)) throw UpmsException.field(field,"包含無效 Unicode 字元");
        }
        String normalized=Normalizer.normalize(value,Normalizer.Form.NFC);
        if(strip) normalized=normalized.strip();
        int length=normalized.codePointCount(0,normalized.length());
        if(length<min||length>max||normalized.codePoints().anyMatch(ch->Character.isISOControl(ch)&&!(newline&&ch=='\n')))
            throw UpmsException.field(field,"文字長度或內容不符合限制");
        return normalized;
    }
}
