package com.xksystem.logistics.tms.internal;
import com.xksystem.logistics.tom.TaskCompletionFacts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** No service dependencies: TOM consumes committed relational facts without a constructor cycle. */
@Component
public class CompletionFactQueryAdapter implements TaskCompletionFacts {
    private final JdbcTemplate jdbc;
    public CompletionFactQueryAdapter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public Set<UUID> completed(UUID orderId,long sourceOrderVersion,List<UUID> taskIds){
        if(orderId==null||sourceOrderVersion<0||taskIds==null||taskIds.size()>100||taskIds.stream().anyMatch(java.util.Objects::isNull))throw new IllegalArgumentException("Completion facts require exact source identity and at most 100 non-null task IDs");
        var ids=new LinkedHashSet<>(taskIds);if(ids.isEmpty())return Set.of();var args=new ArrayList<Object>();args.add(orderId);args.add(sourceOrderVersion);args.add(TmsStore.SCOPE);args.addAll(ids);
        return Set.copyOf(jdbc.query("SELECT w.task_id FROM logistics.tms_completion w JOIN logistics.tms_task_dispatch t ON t.task_id=w.task_id JOIN logistics.tms_execution x ON x.assignment_id=w.assignment_id AND x.phase='COMPLETED' AND x.version=w.to_execution_version WHERE w.order_id=? AND w.source_order_version=? AND t.company_scope=? AND w.task_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",(rs,index)->rs.getObject(1,UUID.class),args.toArray()));
    }
}
