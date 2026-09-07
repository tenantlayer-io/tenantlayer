package io.tenantlayer.strategy;

import java.util.Optional;
import javax.sql.DataSource;

/**
 * Feature 23 — where a tenant's own database lives, for database-per-tenant.
 *
 * <h2>Why this is a seam and not a map of URLs</h2>
 *
 * Deployments disagree about where connection details come from. Some keep them in the
 * tenant registry, some in configuration, some behind a secrets manager that hands out
 * short-lived credentials. Fixing on any one of those would make the strategy unusable for
 * the others, so the strategy depends on this and ships one implementation for the common
 * case.
 *
 * <h2>Empty means unknown, and unknown must stay unknown</h2>
 *
 * An implementation returns empty when it has no database for that tenant. It must not
 * substitute a default: the whole point of database-per-tenant is that the pool is the
 * isolation boundary, so falling back to a shared connection would serve one tenant another
 * tenant's data — the exact failure this strategy exists to make impossible.
 */
public interface TenantDataSourceProvider {

    /**
     * The datasource for this tenant, or empty when there is none.
     *
     * @param tenantId never null or blank
     * @return the tenant's datasource, never a default or fallback
     */
    Optional<DataSource> forTenant(String tenantId);

    /**
     * Releases any pools this provider opened. Implementations that hand back datasources
     * they did not create should do nothing.
     */
    default void shutdown() {
    }
}
