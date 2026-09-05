package io.tenantlayer.strategy;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The default strategy: one shared schema, isolation enforced by Postgres row-level
 * security. This is what 0.1.0 did, unchanged.
 *
 * <h2>Set on checkout, not reset on return</h2>
 *
 * The obvious design — set the tenant when there is one, reset it when the connection goes
 * back to the pool — has a hole. Miss the reset once, for any reason, and the value rides
 * back into the pool and the next borrower inherits it silently.
 *
 * <p>Setting it on <em>every</em> checkout means a connection can never be <em>used</em>
 * carrying a stale tenant, because the value is overwritten before the borrower can issue
 * a statement. It is also one round trip rather than two.
 *
 * <h2>No tenant is written as the empty string</h2>
 *
 * Not left alone. The generated policy compares against
 * {@code nullif(current_setting(...), '')}, so absence evaluates to NULL and matches no
 * rows. Absence of a tenant returns nothing, never everything.
 *
 * <p>Session scope rather than {@code SET LOCAL}, because {@code SET LOCAL} only survives
 * inside an explicit transaction and plenty of reads run in autocommit.
 */
public class RowLevelSecurityStrategy implements TenantConnectionStrategy {

    /** Postgres GUC the generated policies read. */
    public static final String TENANT_SETTING = "tenantlayer.tenant";

    private static final String APPLY_SQL =
            "select set_config('" + TENANT_SETTING + "', ?, false)";

    private final DataSource dataSource;

    public RowLevelSecurityStrategy(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return publishTenant(dataSource.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return publishTenant(dataSource.getConnection(username, password));
    }

    @Override
    public String name() {
        return "ROW_LEVEL_SECURITY";
    }

    private Connection publishTenant(Connection connection) throws SQLException {
        String tenant = TenantContext.current().map(TenantScope::subject).orElse("");
        try (PreparedStatement statement = connection.prepareStatement(APPLY_SQL)) {
            statement.setString(1, tenant);
            statement.execute();
        } catch (SQLException e) {
            // Never hand back a connection whose tenant we could not establish.
            closeQuietly(connection);
            throw e;
        }
        return connection;
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }
}
