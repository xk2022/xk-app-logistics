package com.xksystem.logistics.support;

import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy;
import com.xksystem.logistics.tms.exceptions.ExceptionPolicy;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import com.xksystem.logistics.tom.TaskQueries;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** Named synthetic exception loop, not a formal D04/D05 waiver or resolution rule. */
public final class SyntheticExceptionPolicy {
    private SyntheticExceptionPolicy(){}
    private static final Pattern DRIVER=Pattern.compile("SYNTHETIC_TMS_(IT|DESKTOP|MOBILE)_D[0-9]+");
    public static ExceptionPolicy create(DataSource source)throws SQLException {
        SyntheticEvidencePolicy.requireIsolatedDatabase(source);
        return new ExceptionPolicy(){
            public PolicyMode mode(){return PolicyMode.SYNTHETIC_TEST_ONLY;}
            public boolean allows(Action action,Context context){return context!=null&&context.execution()!=null&&legal(context.execution().phase())&&managerCanRead(context.source(),context.driver());}
            public Optional<ExecutionEvidencePolicy.Requirements> requirements(Context context){return allows(Action.UPLOAD_SUPPLEMENT,context)?Optional.of(new ExecutionEvidencePolicy.Requirements(1,List.of("image/png"),65536,4096)):Optional.empty();}
            public boolean managerCanRead(TaskQueries.Source task,TmsDtos.DriverSnapshot driver){if(task==null||task.taskKind()==null||driver==null||driver.driverCode()==null)return false;var match=DRIVER.matcher(driver.driverCode());return match.matches()&&task.taskKind().startsWith("SYNTHETIC_TMS_"+match.group(1)+"_");}
            private boolean legal(ExecutionPhase phase){return phase==ExecutionPhase.DEPARTED||phase==ExecutionPhase.IN_PROGRESS||phase==ExecutionPhase.AWAITING_COMPLETION;}
        };
    }
}
