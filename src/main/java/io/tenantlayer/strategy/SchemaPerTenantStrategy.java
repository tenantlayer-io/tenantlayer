package io.tenantlayer.strategy;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Feature 22 — one schema per tenant, on a shared database and a shared pool.
 *
 * <h2>The classic bug this avoids</h2>
 *
 * {@code search_path} is session state on a pooled connection, exactly like the tenant GUC.
 * Set it for one request, return the connection without resetting, and the next borrower
 * inherits another tenant's schema — and their unqualified {@code select * from orders}
 * silently reads the wrong rows. Unlike the row-level security case, where a stale setting
 * still meets a policy, here there is nothing downstream to catch it.
 *
 * <p>So the same rule applies as everywhere else in this library: the path is set on
 * <em>every</em> checkout, never reset on return. A connection cannot be used carrying a
 * stale schema because the value is overwritten before the borrower can issue a statement.
 *
 * <h2>Why this is parameterised rather than interpolated</h2>
 *
 * A schema name cannot be a bind parameter in {@code SET search_path TO ...}, and the
 * tenant identifier reaching this class may have arrived in an HTTP header. Building that
 * statement by concatenation would be a SQL injection with attacker-controlled input.
 *
 * <p>{@code set_config('search_path', ?, false)} is the same operation and <em>does</em>
 * take a parameter, so the value never becomes part of the statement text. The identifier
 * is validated as well, so a nonsensical tenant fails with a clear message rather than an
 * unresolved-relation error three frames away.
 *
 * <h2>No tenant means no schema</h2>
 *
 * The path is set to empty rather than left alone or defaulted to {@code public}. An
 * unqualified table then does not resolve and the statement errors. That is a louder
 * failure than the empty result set row-level security gives, and it is deliberate:
 * falling back to {@code public} would quietly read whatever shared schema exists.
 */
public class SchemaPerTenantStrategy implements TenantConnectionStrategy {

    /** Postgres identifiers this strategy is willing to build a schema name from. */
    private static final Pattern SAFE_TENANT = Pattern.compile("[A-Za-z0-9_-]{1,55}");

    private static final String APPLY_SQL = "select set_config('search_path', ?, false)";

    private final DataSource dataSource;
    private final String prefix;

    public SchemaPerTenantStrategy(DataSource dataSource, String prefix) {
        this.dataSource = dataSource;
        this.prefix = prefix == null ? "" : prefix;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applySearchPath(dataSource.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applySearchPath(dataSource.getConnection(username, password));
    }

    @Override
    public String name() {
        return "SCHEMA_PER_TENANT";
    }

    /** Each tenant's tables live in their own schema; there is no policy to expect. */
    @Override
    public boolean expectsRowLevelSecurity() {
        return false;
    }

    /** The schema a tenant's tables live in. */
    public String schemaFor(String tenantId) {
        if (!SAFE_TENANT.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                    "Tenant '" + tenantId + "' cannot be used as a schema name. Schema-per-tenant "
                    + "requires tenant identifiers matching " + SAFE_TENANT.pattern()
                    + "; resolve to an internal id if your external ones are less constrained.");
        }
        return prefix + tenantId;
    }

    private Connection applySearchPath(Connection connection) throws SQLException {
        String path = TenantContext.current()
                .map(TenantScope::subject)
                .map(this::schemaFor)
                .orElse("");   // no tenant: nothing resolves, rather than falling back to public

        try (PreparedStatement statement = connection.prepareStatement(APPLY_SQL)) {
            statement.setString(1, path);
            statement.execute();
        } catch (SQLException e) {
            // Never hand back a connection whose schema we could not establish.
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
