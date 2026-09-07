package io.tenantlayer.core;

import io.tenantlayer.strategy.RowLevelSecurityStrategy;
import io.tenantlayer.strategy.TenantConnectionStrategy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * The DataSource the application sees. Every connection it hands out has been prepared for
 * the acting tenant.
 *
 * <p>The preparation itself belongs to a {@link TenantConnectionStrategy}, because the
 * three isolation strategies differ in a way this class cannot express: row-level security
 * and schema-per-tenant acquire from one pool and then run a statement, while
 * database-per-tenant must choose a different pool <em>before</em> acquiring. So this class
 * no longer decides how — it only guarantees that something did.
 *
 * <p>Constructing it with a plain {@link DataSource} keeps the 0.1.0 behaviour exactly:
 * row-level security, on that pool.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    /**
     * Postgres GUC the generated policies read.
     *
     * @deprecated in spirit rather than in annotation — the canonical home is
     *     {@link RowLevelSecurityStrategy#TENANT_SETTING}. Kept here because it is public
     *     API in 0.1.0 and referenced by user code and by the test fixtures.
     */
    public static final String TENANT_SETTING = RowLevelSecurityStrategy.TENANT_SETTING;

    private final TenantConnectionStrategy strategy;

    /** Row-level security on the given pool — what 0.1.0 did. */
    /**
     * The datasource underneath any tenant-aware wrapping.
     *
     * <p>Some work is cross-tenant by nature and must not be routed by the acting tenant:
     * the tenant registry is read to find out who the tenants <em>are</em>, before any is
     * known, and a migration runner names its own schema explicitly. Under row-level
     * security going through the wrapper is merely pointless; under database-per-tenant
     * there is no connection to hand out at all, so it throws.
     */
    public static DataSource unwrap(DataSource dataSource) {
        DataSource current = dataSource;
        while (current instanceof org.springframework.jdbc.datasource.DelegatingDataSource delegating
                && delegating.getTargetDataSource() != null) {
            current = delegating.getTargetDataSource();
        }
        return current;
    }

    public TenantAwareDataSource(DataSource delegate) {
        this(delegate, new RowLevelSecurityStrategy(delegate));
    }

    public TenantAwareDataSource(DataSource delegate, TenantConnectionStrategy strategy) {
        super(delegate);
        this.strategy = strategy;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return strategy.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return strategy.getConnection(username, password);
    }

    public TenantConnectionStrategy strategy() {
        return strategy;
    }
}
