package io.tenantlayer.registry;

import java.util.List;
import java.util.Optional;

/**
 * Feature 50 — the list of tenants that exist.
 *
 * <p>Two things in the library need this and neither can work without it: iterating every
 * tenant for a scheduled job ({@code forEachTenant}, feature 14), and answering "is this
 * even a real tenant" when a request arrives claiming one.
 *
 * <p>Implementations must be safe to call with no tenant bound to the context. The
 * registry is shared infrastructure, not tenant-scoped data — putting an RLS policy on the
 * registry table would make it invisible to the very code that has not yet worked out
 * which tenant it is serving.
 */
public interface TenantRegistry {

    Optional<TenantRegistration> find(String tenantId);

    List<TenantRegistration> findAll();

    /** Tenant ids in {@link TenantStatus#ACTIVE}, ordered, for iteration helpers. */
    List<String> activeTenantIds();

    void save(TenantRegistration registration);

    boolean delete(String tenantId);

    default boolean exists(String tenantId) {
        return find(tenantId).isPresent();
    }
}
