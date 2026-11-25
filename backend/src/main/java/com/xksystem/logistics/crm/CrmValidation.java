package com.xksystem.logistics.crm;

import com.xksystem.logistics.upms.UpmsException;
import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class CrmValidation {
    private CrmValidation() {}
    private static final Pattern EMAIL=Pattern.compile("^[^\\s@\uFEFF]+@[^\\s@\uFEFF]+\\.[^\\s@\uFEFF]+$",Pattern.UNICODE_CHARACTER_CLASS);
    public static void fields(JsonNode body,Set<String> allowed,Set<String> required) {
        if(body==null||!body.isObject()) throw UpmsException.field("request","請提供 JSON 物件");
        for(String field:body.propertyNames()) if(!allowed.contains(field)) throw UpmsException.field(field,"此欄位不可設定或變更");
        for(String field:required) if(!body.has(field)||body.get(field).isNull()) throw UpmsException.field(field,"此欄位為必填");
        if(required.contains("version")&&body.size()<2) throw UpmsException.field("request","請提供至少一個要更新的欄位");
    }
    public static String string(JsonNode body,String field) {
        var value=body.get(field);
        if(value==null||!value.isString()) throw UpmsException.field(field,"請提供字串");
        return value.asString();
    }
    public static String optional(JsonNode body,String field,int max) {
        var value=body.get(field);
        return value==null||value.isNull()?null:text(string(body,field),field,0,max,false);
    }
    public static String required(JsonNode body,String field,int max,boolean newline) {
        return text(string(body,field),field,1,max,newline);
    }
    public static String text(String value,String field,int min,int max,boolean newline) {
        if(value==null||value.length()>max*4+8) throw UpmsException.field(field,"文字長度超出允許範圍");
        for(int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            if(Character.isHighSurrogate(ch)) {
                if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i))) throw UpmsException.field(field,"包含無效 Unicode 字元");
            } else if(Character.isLowSurrogate(ch)) throw UpmsException.field(field,"包含無效 Unicode 字元");
        }
        String normalized=Normalizer.normalize(value,Normalizer.Form.NFC);
        if(newline) normalized=normalized.replace("\r\n","\n");
        int start=0,end=normalized.length();
        while(start<end&&space(normalized.codePointAt(start)))start+=Character.charCount(normalized.codePointAt(start));
        while(end>start&&space(normalized.codePointBefore(end)))end-=Character.charCount(normalized.codePointBefore(end));
        normalized=normalized.substring(start,end);
        int length=normalized.codePointCount(0,normalized.length());
        if(length<min||length>max||normalized.codePoints().anyMatch(ch->Character.isISOControl(ch)&&!(newline&&ch=='\n')))
            throw UpmsException.field(field,"文字長度或內容不符合限制");
        if(min==0&&normalized.isEmpty())return null;
        return normalized;
    }
    private static boolean space(int ch){return Character.isWhitespace(ch)||Character.isSpaceChar(ch)||ch==0xFEFF;}
    public static String customerCode(JsonNode body) {
        String value=string(body,"customerCode");
        if(!value.matches("[A-Z0-9][A-Z0-9_-]{0,63}"))throw UpmsException.field("customerCode","客戶代碼須為 1 至 64 個大寫英數字、底線或連字號，並以英數字開頭");
        return value;
    }
    public static String email(JsonNode body) {
        String value=optional(body,"email",254);
        if(value!=null&&!EMAIL.matcher(value).matches())throw UpmsException.field("email","請提供有效的電子郵件格式");
        return value;
    }
    public static boolean bool(JsonNode body,String field) {
        var value=body.get(field);
        if(value==null||!value.isBoolean())throw UpmsException.field(field,"請提供布林值");
        return value.asBoolean();
    }
    public static long version(JsonNode body) {
        var value=body.get("version");
        if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0)throw UpmsException.field("version","請提供有效版本");
        return value.asLong();
    }
}
