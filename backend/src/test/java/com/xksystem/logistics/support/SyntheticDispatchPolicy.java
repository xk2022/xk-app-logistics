package com.xksystem.logistics.support;

import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.fms.VehicleQueries;
import com.xksystem.logistics.tms.DispatchEligibilityPolicy;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.ResourcePosition;
import com.xksystem.logistics.tom.TaskQueries;
import java.sql.SQLException;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Explicit, test-classpath-only policy for isolated synthetic scenarios.
 * This configuration is absent from the application JAR and is not a production
 * profile, property, legal qualification rule, or dispatch-policy approval API.
 */
public final class SyntheticDispatchPolicy {
    private SyntheticDispatchPolicy() {}
    private static final Pattern DRIVER = Pattern.compile("SYNTHETIC_TMS_(IT|DESKTOP|MOBILE)_D[0-9]+");
    private static final Pattern VEHICLE = Pattern.compile("SYNTHETIC_TMS_(IT|DESKTOP|MOBILE)_(MAIN|TRAILER|SHARED)");

    public static DispatchEligibilityPolicy create(DataSource dataSource) throws SQLException {
        requireIsolatedDatabase(dataSource);
        return new DispatchEligibilityPolicy() {
            @Override public PolicyMode mode() { return PolicyMode.SYNTHETIC_TEST_ONLY; }

            @Override public boolean driverApproved(DriverQueries.Snapshot driver) {
                return driverScope(driver) != null;
            }

            @Override public boolean vehicleApproved(VehicleQueries.Snapshot vehicle, ResourcePosition position) {
                if (vehicle == null || !vehicle.vehicleTypeEnabled() || vehicle.vehicleTypeCode() == null || position == null) return false;
                var match = VEHICLE.matcher(vehicle.vehicleTypeCode());
                if (!match.matches()) return false;
                String kind = match.group(2);
                return switch (position) {
                    case MAIN -> kind.equals("MAIN") || kind.equals("SHARED");
                    case TRAILER -> kind.equals("TRAILER") || kind.equals("SHARED");
                    case DRIVER -> false;
                };
            }

            @Override public boolean combinationApproved(TaskQueries.Source source, DriverQueries.Snapshot driver,
                    VehicleQueries.Snapshot main, VehicleQueries.Snapshot trailer) {
                String scope = driverScope(driver);
                return scope != null && source != null && source.taskKind() != null
                        && source.taskKind().startsWith("SYNTHETIC_TMS_" + scope + "_")
                        && vehicleApproved(main, ResourcePosition.MAIN)
                        && scope.equals(vehicleScope(main))
                        && (trailer == null || vehicleApproved(trailer, ResourcePosition.TRAILER)
                                && scope.equals(vehicleScope(trailer)) && !main.id().equals(trailer.id()));
            }
        };
    }

    private static void requireIsolatedDatabase(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT current_database()")) {
            if (!result.next() || !"xk_logistics_e2e".equals(result.getString(1))) {
                throw new IllegalStateException("Synthetic dispatch policy requires the isolated xk_logistics_e2e database");
            }
        }
    }

    private static String driverScope(DriverQueries.Snapshot driver) {
        if (driver == null || driver.driverCode() == null) return null;
        var match = DRIVER.matcher(driver.driverCode());
        return match.matches() ? match.group(1) : null;
    }

    private static String vehicleScope(VehicleQueries.Snapshot vehicle) {
        if (vehicle == null || vehicle.vehicleTypeCode() == null) return null;
        var match = VEHICLE.matcher(vehicle.vehicleTypeCode());
        return match.matches() ? match.group(1) : null;
    }
}
