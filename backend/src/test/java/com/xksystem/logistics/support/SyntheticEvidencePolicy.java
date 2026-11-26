package com.xksystem.logistics.support;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.evidence.ExecutionEvidencePolicy;
import com.xksystem.logistics.tms.execution.ExecutionPhase;
import com.xksystem.logistics.tom.TaskQueries;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** Explicit synthetic rules only; never packaged or activated by a production property. */
public final class SyntheticEvidencePolicy {
    private SyntheticEvidencePolicy() {}
    private static final Pattern DRIVER=Pattern.compile("SYNTHETIC_TMS_(IT|DESKTOP|MOBILE)_D[0-9]+");
    public static void requireIsolatedDatabase(DataSource source)throws SQLException {
        try(var connection=source.getConnection();var statement=connection.createStatement();var result=statement.executeQuery("SELECT current_database()")) {
            if(!result.next()||!"xk_logistics_e2e".equals(result.getString(1)))throw new IllegalStateException("Synthetic evidence requires the isolated xk_logistics_e2e database");
        }
    }
    public static ExecutionEvidencePolicy create(DataSource dataSource)throws SQLException {
        requireIsolatedDatabase(dataSource);
        return new ExecutionEvidencePolicy() {
            public PolicyMode mode(){return PolicyMode.SYNTHETIC_TEST_ONLY;}
            public Optional<Requirements> requirements(TaskQueries.Source source,DriverQueries.Snapshot driver,ExecutionPhase phase) {
                return canRead(source,driver)&&(phase==ExecutionPhase.DEPARTED||phase==ExecutionPhase.IN_PROGRESS)
                        ?Optional.of(new Requirements(1,List.of("image/png"),65536,4096)):Optional.empty();
            }
            public boolean canRead(TaskQueries.Source source,DriverQueries.Snapshot driver) {
                if(source==null||source.taskKind()==null||driver==null||driver.driverCode()==null)return false;
                var match=DRIVER.matcher(driver.driverCode());
                return match.matches()&&source.taskKind().startsWith("SYNTHETIC_TMS_"+match.group(1)+"_");
            }
        };
    }
}
