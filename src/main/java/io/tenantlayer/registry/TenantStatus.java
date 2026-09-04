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
    SUSPENDED;

    public static TenantStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        return TenantStatus.valueOf(value.trim().toUpperCase());
    }
}
