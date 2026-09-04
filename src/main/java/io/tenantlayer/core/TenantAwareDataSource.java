package io.tenantlayer.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Publishes the current tenant onto every connection handed out of the pool.
 *
 * <h2>Why on checkout, and why unconditionally</h2>
 *
 * The obvious implementation — set the tenant when there is one, reset it when the
 * connection is returned — has a hole: if the reset is skipped for any reason (an
 * exception on the return path, a code path that bypasses close), the value rides back
 * into the pool and the next borrower silently inherits it.
 *
 * So this sets the value on <em>every</em> checkout, to the current tenant or to the empty
 * string when there is none. A connection can therefore never be used carrying a stale
 * tenant, because the value is overwritten before the borrower can issue a statement. It
 * also costs one round trip rather than two.
 *
 * The empty string is deliberate: the policy compares against
 * {@code nullif(current_setting(...), '')}, so "no tenant" evaluates to NULL and matches
 * no rows. Absence of a tenant returns nothing, never everything.
 *
 * <p>Session scope ({@code is_local = false}) rather than {@code SET LOCAL}, because
 * {@code SET LOCAL} only survives inside an explicit transaction and plenty of reads run
 * in autocommit.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    /** Postgres GUC the generated RLS policies read. */
    public static final String TENANT_SETTING = "tenantlayer.tenant";

    private static final String APPLY_SQL = "select set_config('" + TENANT_SETTING + "', ?, false)";

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return publishTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return publishTenant(super.getConnection(username, password));
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
