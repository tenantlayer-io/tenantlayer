package io.tenantlayer.strategy;

import io.tenantlayer.core.NoTenantException;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Feature 23 — one database per tenant, each behind its own pool.
 *
 * <h2>The pool is the isolation</h2>
 *
 * Row-level security and schema-per-tenant both hand out a connection to a shared database
 * and then narrow what it can see. This does not narrow anything: a connection for tenant A
 * is physically attached to tenant A's database, so there is no policy to get wrong and no
 * session variable to leak across a pooled checkout. That is the strategy's whole appeal,
 * and it is why {@link #expectsRowLevelSecurity()} is false — a policy here would guard
 * against a mistake that cannot be made.
 *
 * <h2>Why there is no fallback, ever</h2>
 *
 * With no tenant bound, or a tenant the provider does not recognise, this throws before a
 * connection exists. It is tempting to fall back to the application's main datasource so
 * that startup probes and stray background jobs keep working — but that datasource is
 * somebody's database. Serving it to an unrecognised tenant is precisely the cross-tenant
 * read this strategy exists to make impossible, so the failure is loud instead.
 *
 * <p>This is a louder failure than the other two strategies give on the no-tenant path:
 * row-level security returns an empty result set, schema-per-tenant raises an unresolved
 * relation, and this throws. All three are safe; only this one is impossible to ignore.
 *
 * <h2>Migrations are not optional here</h2>
 *
 * Every tenant has its own physical database, so schema changes must be applied to each of
 * them. {@link #schemaFor} is empty because tenants share a schema <em>name</em> — they are
 * simply in different databases — which is why {@link #migratesPerTenant()} exists and
 * cannot be inferred from the schema alone.
 */
public class DatabasePerTenantStrategy implements TenantConnectionStrategy {

    private final TenantDataSourceProvider provider;

    public DatabasePerTenantStrategy(TenantDataSourceProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("a TenantDataSourceProvider is required");
        }
        this.provider = provider;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return dataSourceForCurrentTenant().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return dataSourceForCurrentTenant().getConnection(username, password);
    }

    private DataSource dataSourceForCurrentTenant() {
        String tenant = TenantContext.current()
                .map(TenantScope::subject)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new NoTenantException(
                        "database-per-tenant needs a tenant before a connection can be chosen; "
                                + "no tenant is bound"));

        return provider.forTenant(tenant).orElseThrow(() -> new UnknownTenantDatabaseException(tenant));
    }

    @Override
    public String name() {
        return "database-per-tenant";
    }

    /** No policy to enforce: the connection cannot reach another tenant's rows. */
    @Override
    public boolean expectsRowLevelSecurity() {
        return false;
    }

    /** Each tenant has its own database, so each needs the migrations run against it. */
    @Override
    public boolean migratesPerTenant() {
        return true;
    }

    @Override
    public Optional<DataSource> dataSourceFor(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? Optional.empty() : provider.forTenant(tenantId);
    }
}
