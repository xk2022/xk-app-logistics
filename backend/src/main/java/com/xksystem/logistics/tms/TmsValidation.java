package com.xksystem.logistics.tms;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

public final class TmsValidation {
    private TmsValidation() {}
    public static final long MAX=9_007_199_254_740_991L;
    public enum Action { ASSIGN, REVOKE, REASSIGN }
    public record Command(long expectedGeneration,UUID driverId,UUID mainVehicleId,UUID trailerId,String reason) {}
    private static final Pattern UUID_TEXT=Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public static JsonNode parse(byte[] bytes){
        try{String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode value=JSON.readTree(raw);if(value==null)throw TmsFailure.invalid("request","請提供 JSON 物件");unicodeTree(value);return value;
        }catch(TmsFailure failure){throw failure;}catch(CharacterCodingException|RuntimeException failure){throw TmsFailure.invalid("request","JSON 或 UTF-8 格式不合法或包含重複欄位");}
    }
    public static Command command(JsonNode node,Action action){
        Set<String> fields=switch(action){case ASSIGN->Set.of("expectedGeneration","driverId","mainVehicleId","trailerId");case REVOKE->Set.of("expectedGeneration","reason");case REASSIGN->Set.of("expectedGeneration","driverId","mainVehicleId","trailerId","reason");};
        if(node==null||!node.isObject())throw TmsFailure.invalid("request","請提供 JSON 物件");
        for(String field:node.propertyNames())if(!fields.contains(field))throw TmsFailure.invalid(field,"不接受此欄位");
        for(String field:fields)if(!node.has(field))throw TmsFailure.invalid(field,"此欄位必須明確提供");
        long generation=integer(node.get("expectedGeneration"),"expectedGeneration");
        UUID driver=null,main=null,trailer=null;if(action!=Action.REVOKE){driver=uuid(string(node,"driverId"),"driverId");main=uuid(string(node,"mainVehicleId"),"mainVehicleId");
            if(!node.get("trailerId").isNull())trailer=uuid(string(node,"trailerId"),"trailerId");
            if(main.equals(trailer))throw TmsFailure.invalid("trailerId","主車與拖板不可使用同一車輛");}
        String reason=action==Action.ASSIGN?null:text(string(node,"reason"),"reason",500,true);
        if(action!=Action.ASSIGN&&(reason==null||reason.isEmpty()))throw TmsFailure.invalid("reason","請提供撤派或改派原因");
        return new Command(generation,driver,main,trailer,reason);
    }
    public static String key(String raw){if(raw==null||!raw.matches("[A-Za-z0-9._:-]{1,128}"))throw TmsFailure.invalid("Idempotency-Key","請提供單一有效重送識別");return raw;}
    public static UUID uuid(String raw,String field){if(raw==null||!UUID_TEXT.matcher(raw).matches())throw TmsFailure.invalid(field,"請提供完整 UUID");return UUID.fromString(raw);}
    public static long integer(JsonNode value,String field){
        if(value==null||!value.isNumber())throw TmsFailure.invalid(field,"請提供有效整數");
        try{BigDecimal number=value.decimalValue();if(number.signum()<0||number.compareTo(BigDecimal.valueOf(MAX))>0||number.signum()!=0&&number.stripTrailingZeros().scale()>0)throw new ArithmeticException();return number.signum()==0?0:number.longValueExact();}
        catch(ArithmeticException failure){throw TmsFailure.invalid(field,"整數須為 0 至 9007199254740991");}
    }
    private static String string(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isString())throw TmsFailure.invalid(field,"請提供字串");return value.asString();}
    public static String text(String raw,String field,int maximum,boolean multiline){
        if(raw==null)throw TmsFailure.invalid(field,"請提供字串");unicode(raw);String value=Normalizer.normalize(raw,Normalizer.Form.NFC);int begin=0,end=value.length();
        while(begin<end&&space(value.codePointAt(begin)))begin+=Character.charCount(value.codePointAt(begin));while(end>begin&&space(value.codePointBefore(end)))end-=Character.charCount(value.codePointBefore(end));value=value.substring(begin,end);
        if(value.codePointCount(0,value.length())>maximum||value.codePoints().anyMatch(cp->Character.isISOControl(cp)&&!(multiline&&(cp=='\n'||cp=='\t'||cp=='\r'))))throw TmsFailure.invalid(field,"文字長度或內容不符合限制");return value;
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xFEFF;}
    private static void unicode(String value){for(int i=0;i<value.length();i++){char cp=value.charAt(i);if(Character.isHighSurrogate(cp)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw TmsFailure.invalid("request","包含無效 Unicode 字元");}else if(Character.isLowSurrogate(cp))throw TmsFailure.invalid("request","包含無效 Unicode 字元");}}
    private static void unicodeTree(JsonNode node){if(node.isString())unicode(node.asString());else if(node.isArray())node.forEach(TmsValidation::unicodeTree);else if(node.isObject())for(String key:node.propertyNames()){unicode(key);unicodeTree(node.get(key));}}
    public static String hash(JsonNode original){try{StringBuilder output=new StringBuilder();canonical(original,output);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
    private static void canonical(JsonNode node,StringBuilder output){
        if(node.isObject()){output.append('{');var keys=new ArrayList<>(node.propertyNames());keys.sort(TmsValidation::compareCodepoints);boolean comma=false;for(String key:keys){if(comma)output.append(',');comma=true;output.append(JSON.writeValueAsString(key)).append(':');canonical(node.get(key),output);}output.append('}');}
        else if(node.isArray()){output.append('[');for(int i=0;i<node.size();i++){if(i>0)output.append(',');canonical(node.get(i),output);}output.append(']');}
        else if(node.isNumber())output.append(integer(node,"expectedGeneration"));else output.append(JSON.writeValueAsString(node));
    }
    private static int compareCodepoints(String a,String b){var x=a.codePoints().iterator();var y=b.codePoints().iterator();while(x.hasNext()&&y.hasNext()){int result=Integer.compare(x.nextInt(),y.nextInt());if(result!=0)return result;}return Boolean.compare(x.hasNext(),y.hasNext());}
}
