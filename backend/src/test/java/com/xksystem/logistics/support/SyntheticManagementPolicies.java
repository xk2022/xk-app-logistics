package com.xksystem.logistics.support;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.TmsDtos;
import com.xksystem.logistics.tms.confirmation.ManagementCompletionPolicy;
import com.xksystem.logistics.tom.TaskQueries;
import com.xksystem.logistics.tom.closure.OrderClosurePolicy;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
/** Named isolated administrative workflow, not formal legal/safety responsibility. */
public final class SyntheticManagementPolicies {
 private SyntheticManagementPolicies(){} private static final Pattern DRIVER=Pattern.compile("SYNTHETIC_TMS_(IT|DESKTOP|MOBILE)_D[0-9]+");private static final Pattern TASK=Pattern.compile("SYNTHETIC_TMS_(IT|DESKTOP|MOBILE)_[\\s\\S]*");
 public static ManagementCompletionPolicy confirmation(DataSource data)throws SQLException{SyntheticEvidencePolicy.requireIsolatedDatabase(data);return new ManagementCompletionPolicy(){public PolicyMode mode(){return PolicyMode.SYNTHETIC_TEST_ONLY;}public boolean allows(TaskQueries.Source source,TmsDtos.DriverSnapshot driver){if(source==null||source.taskKind()==null||driver==null||driver.driverCode()==null)return false;var d=DRIVER.matcher(driver.driverCode());return d.matches()&&source.taskKind().startsWith("SYNTHETIC_TMS_"+d.group(1)+"_");}};}
 public static OrderClosurePolicy closure(DataSource data)throws SQLException{SyntheticEvidencePolicy.requireIsolatedDatabase(data);return new OrderClosurePolicy(){public PolicyMode mode(){return PolicyMode.SYNTHETIC_TEST_ONLY;}public boolean allows(List<TaskContext> tasks){if(tasks.isEmpty())return false;String scope=null;for(var task:tasks){if(task.taskKind()==null)return false;var t=TASK.matcher(task.taskKind());if(!t.matches())return false;if(scope==null)scope=t.group(1);if(!scope.equals(t.group(1)))return false;
   // A missing assignment still projects TASKS_INCOMPLETE; it can never become a successful closure.
   if(task.driverCode()!=null){var d=DRIVER.matcher(task.driverCode());if(!d.matches()||!scope.equals(d.group(1)))return false;}if(Boolean.FALSE.equals(task.requiredTask()))return false;}return true;}};}
}
