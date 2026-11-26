package com.xksystem.logistics.tom.closure;
import com.xksystem.logistics.tms.TmsFailure;
import com.xksystem.logistics.tms.TmsValidation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
public final class ClosureValidation {
 private ClosureValidation(){} public static final int JSON_CAP=16384;
 public record Command(long expectedVersion,String reason,String hash){}
 public record Query(int page,int size,String state,UUID orderId){}
 public static Command command(byte[] raw,boolean confirmation){try{
  if(raw.length>JSON_CAP)throw new ClosureFailure(413,"E0413","請求超過傳輸上限",null);var node=TmsValidation.parse(raw);String flag=confirmation?"confirmCompletion":"confirmClosure";
  if(!node.isObject()||!node.propertyNames().equals(Set.of("expectedVersion",flag,"reason")))throw invalid("request","請提供指定管理欄位");
  long version=TmsValidation.integer(node.get("expectedVersion"),"expectedVersion");if(!node.get(flag).isBoolean()||!node.get(flag).asBoolean())throw invalid(flag,"請明確確認本次操作");
  var text=node.get("reason");if(!text.isString())throw invalid("reason","請提供原文原因");String reason=rawReason(text.asString());
  var canonical=tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("expectedVersion",version).put(flag,true).put("reason",reason);
  return new Command(version,reason,TmsValidation.hash(canonical));
 }catch(TmsFailure error){throw new ClosureFailure(error.status(),error.code(),error.getMessage(),error.data());}}
 public static String rawReason(String value){if(value==null||value.codePointCount(0,value.length())>1000||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff))throw invalid("reason","請提供 1 至 1000 字的原因");for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw invalid("reason","Unicode 不合法");}else if(Character.isLowSurrogate(c))throw invalid("reason","Unicode 不合法");}return value;}
 public static Query query(MultiValueMap<String,String> values,String scope){Set<String> allowed=switch(scope){case "confirmations"->Set.of("page","size","state","orderId");case "worklist"->Set.of("page","size","state");case "page"->Set.of("page","size");default->throw new IllegalArgumentException("Unknown query scope");};
  for(var entry:values.entrySet())if(!allowed.contains(entry.getKey())||entry.getValue().size()!=1)throw invalid("query","不接受未知或重複參數");
  String state=values.getFirst("state");if(state==null)state=scope.equals("confirmations")?"PENDING":"OPEN";
  if(scope.equals("confirmations")&&!Set.of("PENDING","CONFIRMED","ALL").contains(state)||scope.equals("worklist")&&!Set.of("OPEN","RECORDED","LEGACY_CLOSED","ALL").contains(state))throw invalid("state","請選擇合法狀態");
  return new Query(number(values.getFirst("page"),0,0,100000,"page"),number(values.getFirst("size"),20,1,100,"size"),state,values.getFirst("orderId")==null?null:uuid(values.getFirst("orderId"),"orderId"));
 }
 private static int number(String value,int fallback,int min,int max,String field){if(value==null)return fallback;try{if(!value.matches("[0-9]{1,6}"))throw new NumberFormatException();int parsed=Integer.parseInt(value);if(parsed<min||parsed>max)throw new NumberFormatException();return parsed;}catch(NumberFormatException failure){throw invalid(field,"查詢數值超出範圍");}}
 public static UUID uuid(String value,String field){try{return TmsValidation.uuid(value,field);}catch(TmsFailure failure){throw new ClosureFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}}
 public static String key(String value){try{return TmsValidation.key(value);}catch(TmsFailure failure){throw new ClosureFailure(failure.status(),failure.code(),failure.getMessage(),failure.data());}}
 public static byte[] bounded(InputStream in,long length)throws IOException{if(length>JSON_CAP)throw new ClosureFailure(413,"E0413","請求超過傳輸上限",null);var out=new ByteArrayOutputStream();byte[] block=new byte[4096];int count=0;while(count<=JSON_CAP){int read=in.read(block,0,Math.min(block.length,JSON_CAP+1-count));if(read<0)return out.toByteArray();if(read==0)continue;count+=read;if(count>JSON_CAP)throw new ClosureFailure(413,"E0413","請求超過傳輸上限",null);out.write(block,0,read);}throw new ClosureFailure(413,"E0413","請求超過傳輸上限",null);}
 private static ClosureFailure invalid(String field,String message){return ClosureFailure.invalid(field,message);}
}
