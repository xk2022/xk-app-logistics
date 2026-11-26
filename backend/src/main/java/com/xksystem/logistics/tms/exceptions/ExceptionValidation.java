package com.xksystem.logistics.tms.exceptions;

import com.xksystem.logistics.tms.TmsFailure;
import com.xksystem.logistics.tms.TmsValidation;
import com.xksystem.logistics.tms.evidence.EvidenceFailure;
import com.xksystem.logistics.tms.evidence.EvidenceValidation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ExceptionValidation {
    private ExceptionValidation(){}
    public static final int JSON_CAP=16384,PHOTO_CAP=1048576;
    public record Query(int page,int size,ExceptionStatus status,UUID assignmentId) {}
    public record Input(long version,String text,List<UUID> evidenceIds,String hash) {}
    public static Input report(byte[] raw){return input(raw,"report");}
    public static Input management(byte[] raw){return input(raw,"management");}
    public static Input submit(byte[] raw){return input(raw,"submit");}
    private static Input input(byte[] raw,String kind) {
        try {
            JsonNode node=TmsValidation.parse(raw);String version=kind.equals("report")?"expectedExecutionVersion":"expectedVersion";
            String text=kind.equals("report")?"description":kind.equals("submit")?"note":"reason";
            var expected=kind.equals("submit")?Set.of(version,text,"evidenceIds"):Set.of(version,text);
            if(!node.isObject()||!node.propertyNames().equals(expected))throw ExceptionFailure.invalid("request","請提供指定欄位");
            long number=TmsValidation.integer(node.get(version),version);JsonNode value=node.get(text);
            if(!value.isString())throw ExceptionFailure.invalid(text,"請提供文字");
            String original=rawText(value.asString(),text,kind.equals("report")?2000:1000);
            var ids=new TreeSet<UUID>(java.util.Comparator.comparing(UUID::toString));
            if(kind.equals("submit")) {
                JsonNode values=node.get("evidenceIds");if(!values.isArray()||values.size()>32)throw ExceptionFailure.invalid("evidenceIds","請提供不超過 32 個不重複證據");
                for(var id:values)if(!id.isString()||!ids.add(uuid(id.asString(),"evidenceIds")))throw ExceptionFailure.invalid("evidenceIds","證據識別必須有效且不重複");
            }
            var canonical=JsonMapper.builder().build().createObjectNode().put(version,number).put(text,original);
            if(kind.equals("submit")){var array=canonical.putArray("evidenceIds");ids.forEach(id->array.add(id.toString()));}
            return new Input(number,original,List.copyOf(ids),TmsValidation.hash(canonical));
        }catch(TmsFailure failure){throw new ExceptionFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}
    }
    public static String rawText(String value,String field,int maximum) {
        if(value==null||value.codePointCount(0,value.length())>maximum||value.codePoints().noneMatch(cp->!space(cp)))throw ExceptionFailure.invalid(field,"請提供長度符合限制的非空白文字");
        for(int i=0;i<value.length();i++){char cp=value.charAt(i);if(Character.isHighSurrogate(cp)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw ExceptionFailure.invalid(field,"包含無效 Unicode");}else if(Character.isLowSurrogate(cp))throw ExceptionFailure.invalid(field,"包含無效 Unicode");}
        return value;
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff;}
    public static UUID uuid(String value,String field){try{return EvidenceValidation.uuid(value,field);}catch(EvidenceFailure failure){throw new ExceptionFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}}
    public static String key(String value){try{return EvidenceValidation.key(value);}catch(EvidenceFailure failure){throw new ExceptionFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}}
    public static long headerVersion(String value,String field) {
        if(value==null||!value.matches("0|[1-9][0-9]{0,15}"))throw ExceptionFailure.invalid(field,"請提供單一有效版本");
        try{long parsed=Long.parseLong(value);if(parsed>TmsValidation.MAX)throw new NumberFormatException();return parsed;}
        catch(NumberFormatException invalid){throw ExceptionFailure.invalid(field,"版本超出範圍");}
    }
    public static byte[] bounded(InputStream input,long length,int maximum)throws IOException {
        if(length>maximum)throw new ExceptionFailure(413,"E0413","請求超過傳輸上限",null);
        var output=new ByteArrayOutputStream();byte[] block=new byte[8192];int total=0;
        while(total<=maximum){int read=input.read(block,0,Math.min(block.length,maximum+1-total));if(read<0)return output.toByteArray();if(read==0)continue;total+=read;if(total>maximum)throw new ExceptionFailure(413,"E0413","請求超過傳輸上限",null);output.write(block,0,read);}
        throw new ExceptionFailure(413,"E0413","請求超過傳輸上限",null);
    }
    public static Query query(MultiValueMap<String,String> values,String scope) {
        Set<String> allowed=scope.equals("manager")?Set.of("page","size","status","assignmentId"):scope.equals("self")?Set.of("page","size","status"):Set.of("page","size");
        for(var entry:values.entrySet())if(!allowed.contains(entry.getKey())||entry.getValue().size()!=1)throw ExceptionFailure.invalid("query","不接受未知或重複查詢參數");
        ExceptionStatus status=null;if(values.getFirst("status")!=null)try{status=ExceptionStatus.valueOf(values.getFirst("status"));}catch(IllegalArgumentException invalid){throw ExceptionFailure.invalid("status","請選擇有效狀態");}
        UUID assignment=values.getFirst("assignmentId")==null?null:uuid(values.getFirst("assignmentId"),"assignmentId");
        return new Query(number(values.getFirst("page"),0,0,100000,"page"),number(values.getFirst("size"),20,1,100,"size"),status,assignment);
    }
    private static int number(String value,int fallback,int min,int max,String field){if(value==null)return fallback;try{if(!value.matches("[0-9]{1,6}"))throw new NumberFormatException();int parsed=Integer.parseInt(value);if(parsed<min||parsed>max)throw new NumberFormatException();return parsed;}catch(NumberFormatException invalid){throw ExceptionFailure.invalid(field,"查詢數值超出範圍");}}
}
