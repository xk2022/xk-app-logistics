package com.xksystem.logistics.adm.internal;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
interface DefinitionRepository extends JpaRepository<DefinitionEntity,String> {
 List<DefinitionEntity> findAllByOrderByKeyAsc();
}
