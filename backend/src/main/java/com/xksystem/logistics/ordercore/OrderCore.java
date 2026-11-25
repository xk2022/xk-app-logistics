package com.xksystem.logistics.ordercore;

import java.util.UUID;

/** Shared order identity model. Its table is owned by the TOM aggregate persistence in this slice. */
public record OrderCore(UUID id,String orderNo,Lifecycle lifecycleStatus) {
    public enum Lifecycle { OPEN, CANCELLED, CLOSED }
    public static OrderCore draft(UUID id){return new OrderCore(id,"ORD-"+id,Lifecycle.OPEN);}
    public static String taskNo(UUID id){return "TSK-"+id;}
}
