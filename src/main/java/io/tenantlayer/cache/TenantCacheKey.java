package io.tenantlayer.cache;

import java.io.Serializable;
import java.util.Objects;

/**
 * A cache key qualified by the tenant that produced it.
 *
 * <p>Serializable and with a stable {@code toString}, because cache providers differ in
 * how they derive the physical key: an in-memory cache uses {@code equals}/{@code hashCode},
 * while Redis usually renders the key to a string. Both need to be right, or two tenants
 * collide in one of them and not the other — which is the worst possible way for this to
 * fail, because it would pass a test against Caffeine and leak against Redis.
 */
public record TenantCacheKey(String tenant, Object key) implements Serializable {

    public TenantCacheKey {
        Objects.requireNonNull(tenant, "tenant must not be null");
    }

    @Override
    public String toString() {
        return tenant + "::" + key;
    }
}
