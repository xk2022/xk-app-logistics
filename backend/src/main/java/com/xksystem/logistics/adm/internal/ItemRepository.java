package com.xksystem.logistics.adm.internal;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
interface ItemRepository extends JpaRepository<ItemEntity,UUID>, JpaSpecificationExecutor<ItemEntity> {
 interface ItemLookup {
  UUID getId(); UUID getCategoryId(); String getCategoryCode(); String getCode(); String getLabel();
  boolean getCategoryEnabled(); boolean getItemEnabled(); long getVersion();
 }
 @Query("select i.id as id,i.categoryId as categoryId,c.code as categoryCode,i.code as code,i.label as label,c.enabled as categoryEnabled,i.enabled as itemEnabled,i.version as version from ItemEntity i, CategoryEntity c where i.categoryId=c.id and c.code=:code and i.id=:id")
 Optional<ItemLookup> snapshot(@Param("code") String categoryCode,@Param("id") UUID itemId);
 Optional<ItemEntity> findByCategoryIdAndCode(UUID categoryId,String code);
 @Query("select i from ItemEntity i, CategoryEntity c where i.categoryId=c.id and c.id=:id and c.enabled=true and i.enabled=true order by i.sortOrder asc,i.code asc,i.id asc")
 List<ItemEntity> enabledOptions(@Param("id") UUID categoryId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from ItemEntity i where i.id=:id") Optional<ItemEntity> locked(@Param("id") UUID id);
}
