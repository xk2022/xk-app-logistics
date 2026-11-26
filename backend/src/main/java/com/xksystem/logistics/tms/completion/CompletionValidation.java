package com.xksystem.logistics.tms.completion;
import com.xksystem.logistics.tms.TmsFailure;
import com.xksystem.logistics.tms.TmsValidation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
public final class CompletionValidation {
    private CompletionValidation(){}
    public static final int JSON_CAP=16384;
    public record Command(long expectedVersion,List<UUID> evidenceIds,String hash) {}
    public static Command command(byte[] raw){try{
        if(raw.length>JSON_CAP)throw new CompletionFailure(413,"E0413","請求超過傳輸上限",null);
        var node=TmsValidation.parse(raw);
        if(!node.isObject()||!node.propertyNames().equals(Set.of("expectedVersion","evidenceIds","confirmSafeRelease")))throw CompletionFailure.invalid("request","請提供指定完成欄位");
        long version=TmsValidation.integer(node.get("expectedVersion"),"expectedVersion");
        var confirmed=node.get("confirmSafeRelease");if(!confirmed.isBoolean()||!confirmed.asBoolean())throw CompletionFailure.invalid("confirmSafeRelease","請明確確認完成及安全釋放");
        var ids=node.get("evidenceIds");if(!ids.isArray()||ids.size()>32)throw CompletionFailure.invalid("evidenceIds","證據不得超過 32 筆");
        var sorted=new TreeSet<UUID>(java.util.Comparator.comparing(UUID::toString));
        for(var id:ids)if(!id.isString()||!sorted.add(TmsValidation.uuid(id.asString(),"evidenceIds")))throw CompletionFailure.invalid("evidenceIds","證據識別必須有效且不重複");
        var canonical=JsonMapper.builder().build().createObjectNode().put("expectedVersion",version).put("confirmSafeRelease",true);
        var array=canonical.putArray("evidenceIds");sorted.forEach(id->array.add(id.toString()));
        return new Command(version,List.copyOf(sorted),TmsValidation.hash(canonical));
    }catch(TmsFailure error){throw new CompletionFailure(error.status(),error.code(),error.getMessage(),error.data());}}
    public static UUID uuid(String value,String field){try{return TmsValidation.uuid(value,field);}catch(TmsFailure error){throw new CompletionFailure(error.status(),error.code(),error.getMessage(),error.data());}}
    public static String key(String value){try{return TmsValidation.key(value);}catch(TmsFailure error){throw new CompletionFailure(error.status(),error.code(),error.getMessage(),error.data());}}
    public static byte[] bounded(InputStream input,long length)throws IOException{
        if(length>JSON_CAP)throw new CompletionFailure(413,"E0413","請求超過傳輸上限",null);
        var out=new ByteArrayOutputStream();byte[] block=new byte[4096];int count=0;
        while(count<=JSON_CAP){int read=input.read(block,0,Math.min(block.length,JSON_CAP+1-count));if(read<0)return out.toByteArray();if(read==0)continue;count+=read;if(count>JSON_CAP)throw new CompletionFailure(413,"E0413","請求超過傳輸上限",null);out.write(block,0,read);}
        throw new CompletionFailure(413,"E0413","請求超過傳輸上限",null);
    }
}
