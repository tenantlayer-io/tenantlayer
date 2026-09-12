package io.tenantlayer.registry;

/**
 * Lifecycle state of a registered tenant.
 *
 * <p>The column exists from v0.1 (feature 50) so that the schema does not have to change
 * later. Since feature 54 it is <em>enforced</em> in two places, and they agree: a tenant
 * that is not {@link #ACTIVE} is skipped by {@code forEachTenant} and refused with a 403 by
 * {@code TenantFilter} before any connection is bound. Only ACTIVE is served, so a status
 * added later is fail-closed until something decides otherwise.
 */
public enum TenantStatus {

    ACTIVE,
    SUSPENDED,
    /**
     * The tenant exists but is not finished being set up — its schema, seed data or
     * provisioning hooks have not all completed.
     *
     * <p>It exists so a failed onboarding leaves something explicit rather than something
     * ambiguous. A half-created tenant that is simply absent looks like one nobody asked
     * for; one left ACTIVE looks ready and is not. This is neither: it is not served,
     * {@code forEachTenant} skips it, and the row says plainly what happened.
     */
    PROVISIONING;

    /** Only an ACTIVE tenant is served and iterated. */
    public boolean isServable() {
        return this == ACTIVE;
    }

    public static TenantStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        return TenantStatus.valueOf(value.trim().toUpperCase());
    }
}
