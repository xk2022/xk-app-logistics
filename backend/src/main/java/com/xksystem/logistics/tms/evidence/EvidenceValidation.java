package com.xksystem.logistics.tms.evidence;

import com.xksystem.logistics.tms.TmsFailure;
import com.xksystem.logistics.tms.TmsValidation;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.json.JsonMapper;

public final class EvidenceValidation {
    private EvidenceValidation() {}
    public static final int HARD_CAP=1_048_576;
    public record Progress(long expectedVersion,ExecutionPhase targetPhase,List<UUID> evidenceIds,String hash) {}
    public record Page(int page,int size) {}
    public static Progress progress(byte[] raw) {
        try {
            var node=TmsValidation.parse(raw);
            if(!node.isObject()||!node.propertyNames().equals(Set.of("expectedVersion","targetPhase","evidenceIds")))throw EvidenceFailure.invalid("request","請提供指定進度欄位");
            long version=TmsValidation.integer(node.get("expectedVersion"),"expectedVersion");
            var target=node.get("targetPhase");
            if(!target.isString()||!Set.of("IN_PROGRESS","AWAITING_COMPLETION").contains(target.asString()))throw EvidenceFailure.invalid("targetPhase","請選擇合法目標階段");
            var ids=node.get("evidenceIds");if(!ids.isArray()||ids.size()>32)throw EvidenceFailure.invalid("evidenceIds","請提供不超過 32 筆不重複證據");
            var unique=new TreeSet<UUID>(java.util.Comparator.comparing(UUID::toString));
            for(var id:ids)if(!id.isString()||!unique.add(TmsValidation.uuid(id.asString(),"evidenceIds")))throw EvidenceFailure.invalid("evidenceIds","證據識別必須有效且不重複");
            var canonical=JsonMapper.builder().build().createObjectNode().put("expectedVersion",version).put("targetPhase",target.asString());
            var array=canonical.putArray("evidenceIds");unique.forEach(id->array.add(id.toString()));
            return new Progress(version,ExecutionPhase.valueOf(target.asString()),List.copyOf(unique),TmsValidation.hash(canonical));
        } catch(TmsFailure failure){throw new EvidenceFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}
    }
    public static long headerVersion(String value) {
        if(value==null||!value.matches("0|[1-9][0-9]{0,15}"))throw EvidenceFailure.invalid("X-Execution-Version","請提供單一有效執行版本");
        try{long parsed=Long.parseLong(value);if(parsed>TmsValidation.MAX)throw new NumberFormatException();return parsed;}
        catch(NumberFormatException invalid){throw EvidenceFailure.invalid("X-Execution-Version","執行版本超出範圍");}
    }
    public static UUID uuid(String value,String field) {
        try{return TmsValidation.uuid(value,field);}catch(TmsFailure failure){throw new EvidenceFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}
    }
    public static String key(String value) {
        try{return TmsValidation.key(value);}catch(TmsFailure failure){throw new EvidenceFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}
    }
    public static byte[] bounded(InputStream input,long declaredLength)throws IOException {
        if(declaredLength>HARD_CAP)throw new EvidenceFailure(413,"E0413","照片超過傳輸上限",null);
        var output=new ByteArrayOutputStream();byte[] block=new byte[8192];int total=0;
        while(total<=HARD_CAP) {
            int read=input.read(block,0,Math.min(block.length,HARD_CAP+1-total));
            if(read==-1)return output.toByteArray();if(read==0)continue;
            total+=read;if(total>HARD_CAP)throw new EvidenceFailure(413,"E0413","照片超過傳輸上限",null);
            output.write(block,0,read);
        }
        throw new EvidenceFailure(413,"E0413","照片超過傳輸上限",null);
    }
    public static String digest(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String uploadHash(String path,long version,String mediaType,byte[] bytes) {
        var value=JsonMapper.builder().build().createObjectNode().put("method","POST").put("path",path)
                .put("expectedVersion",version).put("mediaType",mediaType).put("byteSize",bytes.length).put("sha256",digest(bytes));
        return TmsValidation.hash(value);
    }
    public static Page page(MultiValueMap<String,String> query) {
        for(var entry:query.entrySet())if(!Set.of("page","size").contains(entry.getKey())||entry.getValue().size()!=1)throw EvidenceFailure.invalid("query","不接受未知或重複查詢參數");
        return new Page(number(query.getFirst("page"),0,0,100000,"page"),number(query.getFirst("size"),20,1,100,"size"));
    }
    private static int number(String value,int fallback,int min,int max,String field) {
        if(value==null)return fallback;
        try{if(!value.matches("[0-9]{1,6}"))throw new NumberFormatException();int number=Integer.parseInt(value);if(number<min||number>max)throw new NumberFormatException();return number;}
        catch(NumberFormatException invalid){throw EvidenceFailure.invalid(field,"查詢數值超出範圍");}
    }
}
