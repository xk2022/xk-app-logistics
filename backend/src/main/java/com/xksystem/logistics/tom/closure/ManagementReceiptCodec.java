package com.xksystem.logistics.tom.closure;

import com.xksystem.logistics.tms.TmsValidation;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Private owned-ledger encoding only. API values and raw fact bytes are never transformed. */
public final class ManagementReceiptCodec {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private ManagementReceiptCodec(){}
 public static String confirmation(ClosureDtos.ConfirmationResult result){return encode(result,"confirmation");}
 public static String closure(ClosureDtos.Result result){return encode(result,"closure");}
 private static String encode(Object result,String field){String reason,name;if(result instanceof ClosureDtos.ConfirmationResult value){reason=value.confirmation().reason();name=value.confirmation().actor().displayName();}else{var value=(ClosureDtos.Result)result;reason=value.closure().reason();name=value.closure().actor().displayName();}String encodedReason=base64(reason),encodedName=base64(name);ObjectNode node=(ObjectNode)JSON.readTree(JSON.writeValueAsString(result));ObjectNode fact=(ObjectNode)node.get(field);fact.put("reason",encodedReason);((ObjectNode)fact.get("actor")).put("displayName",encodedName);node.put("codecVersion",1);return JSON.writeValueAsString(node);}
 public static ClosureDtos.ConfirmationResult confirmation(String stored){return decode(stored,true,ClosureDtos.ConfirmationResult.class);}
 public static ClosureDtos.Result closure(String stored){return decode(stored,false,ClosureDtos.Result.class);}
 private static <T>T decode(String stored,boolean confirmation,Class<T> type){try{
  var parsed=TmsValidation.parse(stored.getBytes(StandardCharsets.UTF_8));String field=confirmation?"confirmation":"closure";
  shape(parsed,confirmation?Set.of("codecVersion","completionId","assignmentId","taskId","sourceOrderVersion","version","confirmation"):Set.of("codecVersion","orderId","orderNo","lifecycleStatus","acceptanceStatus","version","closure"));
  if(TmsValidation.integer(parsed.get("codecVersion"),"codecVersion")!=1)throw ClosureFailure.corrupt();
  if(confirmation){for(String key:Set.of("completionId","assignmentId","taskId"))uuid(parsed,key);number(parsed,"sourceOrderVersion");}else{uuid(parsed,"orderId");for(String key:Set.of("orderNo","lifecycleStatus","acceptanceStatus"))string(parsed,key);}
  number(parsed,"version");var fact=parsed.get(field);shape(fact,confirmation?Set.of("id","version","confirmedAt","policyMode","reason","actor"):Set.of("id","sourceVersion","version","closedAt","policyMode","managerConfirmed","reason","taskCount","actor"));
  uuid(fact,"id");number(fact,"version");string(fact,confirmation?"confirmedAt":"closedAt");string(fact,"policyMode");
  if(!confirmation){number(fact,"sourceVersion");number(fact,"taskCount");if(!fact.get("managerConfirmed").isBoolean()||!fact.get("managerConfirmed").asBoolean())throw ClosureFailure.corrupt();}
  var actor=fact.get("actor");shape(actor,Set.of("role","displayName"));if(!Set.of("ADMIN","DISPATCHER").contains(string(actor,"role")))throw ClosureFailure.corrupt();
  ObjectNode wire=(ObjectNode)JSON.readTree(JSON.writeValueAsString(parsed));wire.remove("codecVersion");var wireFact=(ObjectNode)wire.get(field);wireFact.put("reason",unbase64(string(fact,"reason")));((ObjectNode)wireFact.get("actor")).put("displayName",unbase64(string(actor,"displayName")));
  return JSON.readValue(JSON.writeValueAsString(wire),type);
 }catch(RuntimeException failure){throw ClosureFailure.corrupt();}}
 private static void shape(JsonNode node,Set<String> fields){if(node==null||!node.isObject()||!node.propertyNames().equals(fields))throw ClosureFailure.corrupt();}
 private static String string(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isString())throw ClosureFailure.corrupt();return value.asString();}
 private static void uuid(JsonNode node,String field){TmsValidation.uuid(string(node,field),field);}
 private static void number(JsonNode node,String field){TmsValidation.integer(node.get(field),field);}
 private static String base64(String value){try{var buffer=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return Base64.getEncoder().encodeToString(bytes);}catch(java.nio.charset.CharacterCodingException failure){throw ClosureFailure.corrupt();}}
 private static String unbase64(String encoded){try{byte[] bytes=Base64.getDecoder().decode(encoded);if(!Base64.getEncoder().encodeToString(bytes).equals(encoded))throw ClosureFailure.corrupt();return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException|IllegalArgumentException failure){throw ClosureFailure.corrupt();}}
}
