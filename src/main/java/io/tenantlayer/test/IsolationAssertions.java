package io.tenantlayer.test;

import io.tenantlayer.core.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;

/**
 * The test every multi-tenant team should have and almost none writes.
 *
 * The assertion is deliberately two-sided. Proving "tenant A sees no rows belonging to B"
 * is worthless on its own — an empty table passes it. So it first proves, on a privileged
 * connection that bypasses the policy, that B's rows genuinely exist.
 */
public final class IsolationAssertions {

    private static volatile DataSource applicationDataSource;
    private static volatile DataSource privilegedDataSource;
    private static volatile String table = "documents";
    private static volatile String tenantColumn = "tenant_id";

    private IsolationAssertions() {
    }

    /**
     * @param application the least-privileged DataSource the app uses, subject to the policy
     * @param privileged  a DataSource that bypasses the policy, used only to prove the
     *                    other tenant's rows are really there
     */
    public static void bind(DataSource application, DataSource privileged) {
        applicationDataSource = application;
        privilegedDataSource = privileged;
    }

    public static void bindTable(String tableName, String tenantColumnName) {
        table = tableName;
        tenantColumn = tenantColumnName;
    }

    public static void assertTenantCannotSee(String otherTenant) {
        String current = TenantContext.current()
                .map(scope -> scope.subject())
                .orElseThrow(() -> new AssertionError(
                        "assertTenantCannotSee(\"" + otherTenant + "\") was called with no tenant in "
                        + "context — the assertion would be meaningless. Wrap the test in @WithTenant."));

        if (current.equals(otherTenant)) {
            throw new AssertionError(
                    "assertTenantCannotSee(\"" + otherTenant + "\") was called while acting as that "
                    + "same tenant.");
        }

        long actuallyExist = count(privilegedDataSource, otherTenant);
        if (actuallyExist == 0) {
            throw new AssertionError(
                    "Tenant \"" + otherTenant + "\" has no rows in " + table + ", so this assertion "
                    + "would pass whether or not isolation works. Seed data for it first.");
        }

        long visible = count(applicationDataSource, otherTenant);
        if (visible != 0) {
            throw new AssertionError(
                    "Isolation breach: acting as \"" + current + "\", " + visible + " of tenant \""
                    + otherTenant + "\"'s " + actuallyExist + " rows in " + table + " were readable.");
        }
    }

    private static long count(DataSource dataSource, String tenant) {
        if (dataSource == null) {
            throw new IllegalStateException(
                    "IsolationAssertions.bind(application, privileged) has not been called");
        }
        String sql = "select count(*) from " + table + " where " + tenantColumn + " = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("counting rows for tenant " + tenant + " failed", e);
        }
    }
}
