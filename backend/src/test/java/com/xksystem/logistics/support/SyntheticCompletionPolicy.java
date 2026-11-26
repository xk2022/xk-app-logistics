package com.xksystem.logistics.support;
import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.completion.CompletionPolicy;
import com.xksystem.logistics.tom.TaskQueries;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;
/** Isolated synthetic latest-readiness rule, never a formal safety approval. */
public final class SyntheticCompletionPolicy {
    private SyntheticCompletionPolicy(){}
    private static final Pattern DRIVER=Pattern.compile("SYNTHETIC_TMS_(IT|DESKTOP|MOBILE)_D[0-9]+");
    public static CompletionPolicy create(DataSource source)throws SQLException{
        SyntheticEvidencePolicy.requireIsolatedDatabase(source);
        return new CompletionPolicy(){
            public PolicyMode mode(){return PolicyMode.SYNTHETIC_TEST_ONLY;}
            public Optional<Requirements> requirements(TaskQueries.Source task,DriverQueries.Snapshot driver){
                if(task==null||task.taskKind()==null||driver==null||driver.driverCode()==null)return Optional.empty();var match=DRIVER.matcher(driver.driverCode());
                return match.matches()&&task.taskKind().startsWith("SYNTHETIC_TMS_"+match.group(1)+"_")?Optional.of(new Requirements("LATEST_AWAITING_TRANSITION",1,List.of("image/png"),65536,4096,true)):Optional.empty();
            }
        };
    }
}
