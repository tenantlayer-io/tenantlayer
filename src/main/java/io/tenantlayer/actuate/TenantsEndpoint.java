package io.tenantlayer.actuate;

import io.tenantlayer.registry.TenantProvisioning;
import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

/**
 * Feature 51 — the registry, operable over HTTP.
 *
 * <h2>Why an actuator endpoint</h2>
 *
 * The registry could only be driven from Java, which makes onboarding a deploy for anyone
 * without a signup form. An actuator endpoint gets three things for free that a controller
 * would have to be given: it is off unless explicitly enabled <em>and</em> explicitly
 * exposed, it inherits whatever protects the rest of your management endpoints, and it
 * lives under {@code /actuator}, which is in {@code tenantlayer.unscoped-paths} by default —
 * so the endpoint that manages tenants is never itself tenant-scoped.
 *
 * <h2>It is off by default, twice</h2>
 *
 * {@code enableByDefault = false} means both of these are required:
 *
 * <pre>
 * management.endpoint.tenants.enabled=true
 * management.endpoints.web.exposure.include=tenants
 * </pre>
 *
 * That is deliberate for an endpoint that can create a tenant. Turning it on is a decision
 * somebody has to make twice, and nobody arrives at it by copying a properties file.
 *
 * <h2>Creating goes through provisioning, not the registry</h2>
 *
 * A write here runs the same sequence as {@code TenantProvisioning.onboard} — migrations,
 * hooks, then ACTIVE. Writing a registry row directly would produce a tenant that exists
 * and does not work, which is the failure the provisioning sequence exists to prevent.
 */
@Endpoint(id = "tenants", enableByDefault = false)
public class TenantsEndpoint {

    private final TenantRegistry registry;
    private final TenantProvisioning provisioning;

    public TenantsEndpoint(TenantRegistry registry, TenantProvisioning provisioning) {
        this.registry = registry;
        this.provisioning = provisioning;
    }

    /** Every tenant, whatever its status. */
    @ReadOperation
    public Map<String, Object> tenants() {
        List<Map<String, Object>> all = registry.findAll().stream().map(TenantsEndpoint::describe).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", all.size());
        body.put("tenants", all);
        return body;
    }

    /** One tenant, or null — which actuator renders as a 404. */
    @ReadOperation
    public Map<String, Object> tenant(@Selector String tenantId) {
        return registry.find(tenantId).map(TenantsEndpoint::describe).orElse(null);
    }

    /**
     * Onboards a tenant: registry row, migrations, hooks, then ACTIVE.
     *
     * <p>Idempotent, like {@code onboard} itself — POSTing an existing active tenant
     * changes nothing rather than failing, so a retried call is safe.
     */
    @WriteOperation
    public Map<String, Object> onboard(String tenantId,
                                       @Nullable String region,
                                       @Nullable String group,
                                       @Nullable String datasourceRef) {
        // No blank check here: TenantRegistration's own constructor rejects a null or
        // blank id, and a second check in front of it can never fail — which a mutation
        // test proved by removing it and watching nothing go red.
        TenantRegistration desired = new TenantRegistration(
                tenantId, TenantStatus.ACTIVE, region, group, datasourceRef, Map.of());
        return describe(provisioning.onboard(desired));
    }

    /**
     * Changes a tenant's status — suspend or reactivate.
     *
     * <p>Deliberately not a way to reach PROVISIONING: that status is owned by the
     * provisioning sequence, and setting it by hand would claim work had happened that
     * had not.
     */
    @WriteOperation
    public Map<String, Object> status(@Selector String tenantId, String status) {
        TenantStatus target = TenantStatus.parse(status);
        if (target == TenantStatus.PROVISIONING) {
            throw new IllegalArgumentException(
                    "PROVISIONING is set by onboarding, not by hand; use onboard to re-run it");
        }
        TenantRegistration current = registry.find(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("no such tenant: " + tenantId));

        TenantRegistration updated = new TenantRegistration(
                current.tenantId(), target, current.region(), current.group(),
                current.datasourceRef(), current.metadata());
        registry.save(updated);
        return describe(updated);
    }

    /**
     * Removes the registry row.
     *
     * <p>It does not delete the tenant's data — no library should decide that for you, and
     * under row-level security the rows are indistinguishable from anyone else's without a
     * tenant bound. Suspending is almost always what was meant.
     */
    @DeleteOperation
    public Map<String, Object> delete(@Selector String tenantId) {
        boolean removed = registry.delete(tenantId);
        return Map.of(
                "tenantId", tenantId,
                "removed", removed,
                "note", "the registry row is gone; this tenant's data is not");
    }

    private static Map<String, Object> describe(TenantRegistration registration) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", registration.tenantId());
        row.put("status", registration.status().name());
        row.put("servable", registration.status().isServable());
        row.put("region", registration.region());
        row.put("group", registration.group());
        row.put("datasourceRef", registration.datasourceRef());
        row.put("metadata", Optional.ofNullable(registration.metadata()).orElse(Map.of()));
        return row;
    }
}
