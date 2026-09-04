package io.tenantlayer.hibernate;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Feature 20 — hands the current tenant to Hibernate, for the discriminator strategy.
 *
 * <p>With a field annotated {@code @TenantId} on an entity, Hibernate consults this on
 * every session: it adds the tenant predicate to reads and stamps the column on writes,
 * without the application writing either. That is the strategy most SaaS applications
 * start with, and it composes with row-level security rather than competing — Hibernate
 * filters what the ORM asks for, Postgres enforces what the connection may see. Only the
 * second one survives a native query.
 *
 * <h2>The empty string is the fail-closed answer</h2>
 *
 * Hibernate requires a non-null identifier. Returning the empty string when no tenant is
 * bound makes reads match nothing and writes stamp a value no tenant uses, so work
 * attempted without a tenant does nothing instead of touching everyone's rows. Returning
 * something like "default" or the first known tenant would be the opposite.
 */
public class TenantContextIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    public static final String NO_TENANT = "";

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantContext.current().map(TenantScope::subject).orElse(NO_TENANT);
    }

    /**
     * False: sessions are not reused across tenants here, and Hibernate's validation would
     * throw when a session outlives the scope that opened it — which happens legitimately
     * whenever a request finishes while an open-in-view session is still being torn down.
     */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
