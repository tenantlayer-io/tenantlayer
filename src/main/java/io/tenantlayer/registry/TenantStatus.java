package io.tenantlayer.registry;

/**
 * Lifecycle state of a registered tenant.
 *
 * <p>The column exists from v0.1 (feature 50) so that the schema does not have to change
 * later. <em>Enforcing</em> it at resolution time is feature 54 and belongs to v0.2 — this
 * enum is carried and reported, not yet used to reject requests. Anything that claims
 * otherwise would be claiming a control the library does not currently apply.
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
