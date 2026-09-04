package io.tenantlayer.registry;

import java.util.Map;
import java.util.Objects;

/**
 * Feature 50 — one row of the tenant registry.
 *
 * <p>Feature 56 is the reason {@code region} and {@code group} are here in v0.1, long
 * before residency (Pro-4) or the MSP module (Pro-6) exist to read them. A registry is the
 * hardest thing in the system to change once it holds production rows: adding a column
 * later means a migration on every deployment plus a backfill nobody has the data for.
 * Both are nullable and unused; they cost a schema no one has to alter twice.
 *
 * <p>{@code datasourceRef} is the same argument for schema- and database-per-tenant
 * routing (features 22 and 23, v0.2/v0.3). It names a datasource; nothing resolves it yet.
 */
public record TenantRegistration(
        String tenantId,
        TenantStatus status,
        String region,
        String group,
        String datasourceRef,
        Map<String, String> metadata) {

    public TenantRegistration {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        status = status == null ? TenantStatus.ACTIVE : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static TenantRegistration of(String tenantId) {
        return new TenantRegistration(tenantId, TenantStatus.ACTIVE, null, null, null, Map.of());
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
