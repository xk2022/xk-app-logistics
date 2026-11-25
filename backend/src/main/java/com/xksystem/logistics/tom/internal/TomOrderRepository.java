package com.xksystem.logistics.tom.internal;
import java.util.UUID;
import org.springframework.data.repository.Repository;
interface TomOrderRepository extends Repository<TomOrderRecord,UUID>,TomOperations {}
