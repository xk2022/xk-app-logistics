package com.xksystem.logistics.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xksystem.logistics.fms.AdministrativeStatus;
import com.xksystem.logistics.fms.DriverQueries;
import com.xksystem.logistics.fms.DutyStatus;
import com.xksystem.logistics.fms.EmploymentStatus;
import com.xksystem.logistics.fms.VehicleQueries;
import com.xksystem.logistics.tms.PolicyMode;
import com.xksystem.logistics.tms.ResourcePosition;
import com.xksystem.logistics.tom.TaskQueries;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SyntheticDispatchPolicyTest {
    @Test void refusesAnyDatabaseOtherThanTheExactIsolatedDatabase() throws Exception {
        for (String name : new String[] {"xk_logistics", "xk_logistics_e2e_backup", "XK_LOGISTICS_E2E"}) {
            var database = database(name);
            assertThatThrownBy(() -> SyntheticDispatchPolicy.create(database.source()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Synthetic dispatch policy requires the isolated xk_logistics_e2e database");
            verify(database.result()).close();
            verify(database.statement()).close();
            verify(database.connection()).close();
        }
    }

    @Test void refusesAnAbsentDatabaseIdentityInsteadOfGrantingAFallbackPolicy() throws Exception {
        var database = database("xk_logistics_e2e");
        when(database.result().next()).thenReturn(false);
        assertThatThrownBy(() -> SyntheticDispatchPolicy.create(database.source()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void propagatesDatabaseVerificationFailureWithoutIssuingAPolicy() throws Exception {
        var database = database("xk_logistics_e2e");
        when(database.statement().executeQuery("SELECT current_database()"))
                .thenThrow(new SQLException("Synthetic database verification failure"));
        assertThatThrownBy(() -> SyntheticDispatchPolicy.create(database.source()))
                .isInstanceOf(SQLException.class);
        verify(database.statement()).close();
        verify(database.connection()).close();
    }

    @Test void onlyExactSyntheticDriverScopesReceiveTheClearlyNamedTestMode() throws Exception {
        var policy = SyntheticDispatchPolicy.create(database("xk_logistics_e2e").source());
        assertThat(policy.mode()).isEqualTo(PolicyMode.SYNTHETIC_TEST_ONLY);
        for (String scope : new String[] {"IT", "DESKTOP", "MOBILE"}) {
            assertThat(policy.driverApproved(driver("SYNTHETIC_TMS_" + scope + "_D1"))).isTrue();
        }
        for (String code : new String[] {"DRIVER-1", "SYNTHETIC_TMS_REAL_D1", "synthetic_tms_it_d1",
                "SYNTHETIC_TMS_IT_D", "SYNTHETIC_TMS_IT_D1_EXTRA", "SYNTHETIC_TMS_IT_D1\n"}) {
            assertThat(policy.driverApproved(driver(code))).isFalse();
        }
        assertThat(policy.driverApproved(null)).isFalse();
        assertThat(policy.driverApproved(driver(null))).isFalse();
    }

    @Test void sharedSyntheticTypeTestsTheCommonVehicleNamespaceWithoutApprovingOtherTypes() throws Exception {
        var policy = SyntheticDispatchPolicy.create(database("xk_logistics_e2e").source());
        var main = vehicle("SYNTHETIC_TMS_IT_MAIN");
        var trailer = vehicle("SYNTHETIC_TMS_IT_TRAILER");
        var shared = vehicle("SYNTHETIC_TMS_IT_SHARED");
        assertThat(policy.vehicleApproved(main, ResourcePosition.MAIN)).isTrue();
        assertThat(policy.vehicleApproved(main, ResourcePosition.TRAILER)).isFalse();
        assertThat(policy.vehicleApproved(trailer, ResourcePosition.MAIN)).isFalse();
        assertThat(policy.vehicleApproved(trailer, ResourcePosition.TRAILER)).isTrue();
        assertThat(policy.vehicleApproved(shared, ResourcePosition.MAIN)).isTrue();
        assertThat(policy.vehicleApproved(shared, ResourcePosition.TRAILER)).isTrue();
        assertThat(policy.vehicleApproved(shared, ResourcePosition.DRIVER)).isFalse();
        assertThat(policy.vehicleApproved(vehicle("FMS_DESKTOP_A"), ResourcePosition.MAIN)).isFalse();
        assertThat(policy.vehicleApproved(vehicle("SYNTHETIC_TMS_IT_MAIN_EXTRA"), ResourcePosition.MAIN)).isFalse();
        assertThat(policy.vehicleApproved(vehicle(null), ResourcePosition.MAIN)).isFalse();
        assertThat(policy.vehicleApproved(null, ResourcePosition.MAIN)).isFalse();
        assertThat(policy.vehicleApproved(main, null)).isFalse();
    }

    @Test void aCombinationRequiresExplicitSyntheticTaskAndEveryResourceFromTheSameScope() throws Exception {
        var policy = SyntheticDispatchPolicy.create(database("xk_logistics_e2e").source());
        var driver = driver("SYNTHETIC_TMS_IT_D1");
        var main = vehicle("SYNTHETIC_TMS_IT_MAIN");
        var trailer = vehicle("SYNTHETIC_TMS_IT_TRAILER");
        var task = source("SYNTHETIC_TMS_IT_FIRST");
        assertThat(policy.combinationApproved(task, driver, main, trailer)).isTrue();
        assertThat(policy.combinationApproved(task, driver, main, null)).isTrue();
        assertThat(policy.combinationApproved(source("一般運送"), driver, main, trailer)).isFalse();
        assertThat(policy.combinationApproved(source("SYNTHETIC_TMS_DESKTOP_FIRST"), driver, main, trailer)).isFalse();
        assertThat(policy.combinationApproved(task, driver("SYNTHETIC_TMS_MOBILE_D1"), main, trailer)).isFalse();
        assertThat(policy.combinationApproved(task, driver, vehicle("SYNTHETIC_TMS_DESKTOP_MAIN"), trailer)).isFalse();
        assertThat(policy.combinationApproved(task, driver, main, vehicle("SYNTHETIC_TMS_MOBILE_TRAILER"))).isFalse();
        assertThat(policy.combinationApproved(task, driver, trailer, main)).isFalse();
        var shared = vehicle("SYNTHETIC_TMS_IT_SHARED");
        assertThat(policy.combinationApproved(task, driver, shared, shared)).isFalse();
        assertThat(policy.combinationApproved(null, driver, main, trailer)).isFalse();
    }

    @Test void archivedSyntheticVehicleTypesDoNotRetainTestPolicyApproval() throws Exception {
        var policy = SyntheticDispatchPolicy.create(database("xk_logistics_e2e").source());
        var original = vehicle("SYNTHETIC_TMS_IT_SHARED");
        var archived = new VehicleQueries.Snapshot(original.id(), original.plateNo(), original.vehicleType(),
                original.vehicleTypeCode(), original.vehicleTypeLabel(), false, original.capacityTon(),
                original.administrativeStatus(), original.version());
        assertThat(policy.vehicleApproved(archived, ResourcePosition.MAIN)).isFalse();
        assertThat(policy.vehicleApproved(archived, ResourcePosition.TRAILER)).isFalse();
        assertThat(policy.combinationApproved(source("SYNTHETIC_TMS_IT_FIRST"),
                driver("SYNTHETIC_TMS_IT_D1"), archived, null)).isFalse();
    }

    private record Database(DataSource source, Connection connection, Statement statement, ResultSet result) {}

    private static Database database(String name) throws SQLException {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT current_database()")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn(name);
        return new Database(source, connection, statement, result);
    }

    private static DriverQueries.Snapshot driver(String code) {
        return new DriverQueries.Snapshot(UUID.randomUUID(), code, "合成司機", null, null,
                EmploymentStatus.ACTIVE, DutyStatus.ON_DUTY, null, null, null, false, null, 0);
    }

    private static VehicleQueries.Snapshot vehicle(String code) {
        return new VehicleQueries.Snapshot(UUID.randomUUID(), "SYNTHETIC-PLATE", UUID.randomUUID(), code,
                "合成車種", true, null, AdministrativeStatus.AVAILABLE, 0);
    }

    private static TaskQueries.Source source(String kind) {
        return new TaskQueries.Source(UUID.randomUUID(), "SYNTHETIC-TASK", UUID.randomUUID(), "SYNTHETIC-ORDER",
                "LOCAL", 1, new TaskQueries.Item("CARGO", null, null, 1, null, null), kind,
                "合成起點", "合成起點地址", "合成終點", "合成終點地址", null, null, null, true);
    }
}
