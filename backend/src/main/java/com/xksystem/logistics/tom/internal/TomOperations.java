package com.xksystem.logistics.tom.internal;
import com.xksystem.logistics.tom.TomPageQuery;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.node.ObjectNode;
interface TomOperations {
 record Replay(String requestHash,UUID orderId,int status,ObjectNode data,String location) {}
 boolean tryKeyLock(String scope,UUID actor,String method,String path,String key);
 Optional<Replay> replay(String scope,UUID actor,String method,String path,String key);
 ObjectNode read(UUID id,String scope,boolean lock);
 ObjectNode page(String scope,TomPageQuery query);
 ObjectNode create(UUID id,String scope,UUID actor,Instant now,ObjectNode draft,String customerName);
 ObjectNode replace(UUID id,String scope,UUID actor,Instant now,ObjectNode draft,String customerName,ObjectNode previous);
 ObjectNode accept(UUID id,String scope,UUID actor,Instant now,long previousVersion);
 ObjectNode close(UUID id,String scope,UUID actor,Instant now,long previousVersion);
 void revision(UUID orderId,ObjectNode result,String trace,UUID actor,Instant now);
 void acceptanceEvent(UUID orderId,long fromVersion,ObjectNode result,String trace,UUID actor,Instant now);
 void remember(String scope,UUID actor,String method,String path,String key,String hash,UUID orderId,int status,ObjectNode data,String location,Instant now);
}
