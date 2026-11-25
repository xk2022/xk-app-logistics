package com.xksystem.logistics.tom.internal;
import jakarta.persistence.*;
import java.util.UUID;
/** Spring Data anchor; all aggregate persistence is implemented by the owned native fragment. */
@Entity @Table(name="tom_order",schema="logistics")
class TomOrderRecord {
 @Id @Column(name="order_id") UUID id;
 @Column(nullable=false) long version;
 protected TomOrderRecord() {}
}
