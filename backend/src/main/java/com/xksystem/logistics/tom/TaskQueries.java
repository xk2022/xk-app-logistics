package com.xksystem.logistics.tom;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Trusted, narrow task source; no HTTP authorization grant or dispatch state is owned here. */
public interface TaskQueries {
    record Item(String itemKind,String containerType,String containerNo,Integer packageQty,String grossWeight,String cbm) {}
    record Contact(String name,String phone) {}
    record Source(UUID taskId,String taskNo,UUID orderId,String orderNo,String orderType,long orderVersion,Item item,
                  String taskKind,String pickupName,String pickupAddress,String deliveryName,String deliveryAddress,
                  Contact siteContact,String plannedStartAt,String plannedEndAt,Boolean requiredTask) {}
    record Current(Source source,boolean accepted,boolean prerequisiteReady) {}
    record Seed(UUID taskId,UUID orderId,String orderNo,String taskNo,long sourceOrderVersion) {}
    Optional<Current> find(UUID taskId);
    /** At most 100 task IDs; current source remains explicit even when no longer OPEN+ACCEPTED. */
    Map<UUID,Current> bulk(List<UUID> taskIds);
    /** Writable caller transaction and caller canonical/authorization locks first; does not require accepted state. */
    Current lockCurrentForDispatch(UUID taskId);
}
