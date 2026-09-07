package io.tenantlayer.strategy;

/**
 * No database is configured for the tenant that asked for a connection.
 *
 * <p>Separate from {@link io.tenantlayer.core.NoTenantException}, which means no tenant was
 * bound at all. This one means a tenant <em>was</em> bound and is not one we know about —
 * a different operational problem with a different fix, and worth being able to tell apart
 * in a log.
 */
public class UnknownTenantDatabaseException extends RuntimeException {

    private final String tenantId;

    public UnknownTenantDatabaseException(String tenantId) {
        super("no database is configured for tenant '" + tenantId
                + "'; database-per-tenant will not fall back to a shared datasource");
        this.tenantId = tenantId;
    }

    public String tenantId() {
        return tenantId;
    }
}
