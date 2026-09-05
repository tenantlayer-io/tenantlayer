package io.tenantlayer.cache;

import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Feature 97 — removes everything one tenant has cached.
 *
 * <p>Needed whenever a tenant is suspended, deleted or moved. Without it, suspending a
 * tenant leaves their data readable from cache for as long as the entries live, which
 * makes "suspended" mean rather less than it sounds.
 *
 * <h2>What it can and cannot do</h2>
 *
 * Spring's {@link Cache} interface has no way to enumerate keys, so eviction by tenant has
 * to reach the native cache. Providers whose native cache is a {@link Map} — the default
 * {@code ConcurrentMapCache}, and Caffeine — are handled here. Anything else (Redis, for
 * instance, where the equivalent is a {@code SCAN} over a key pattern) throws rather than
 * silently doing nothing, because a no-op eviction is worse than an error: you would
 * believe the data was gone.
 */
public class TenantCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(TenantCacheEvictor.class);

    private final CacheManager cacheManager;

    public TenantCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** @return how many entries were removed across all caches. */
    public int evictTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        int removed = 0;
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                removed += evictTenantFrom(cache, tenantId);
            }
        }
        log.info("evicted {} cache entries for tenant '{}'", removed, tenantId);
        return removed;
    }

    private int evictTenantFrom(Cache cache, String tenantId) {
        Object native_ = cache.getNativeCache();

        // Caffeine exposes a Map view rather than being one.
        if (!(native_ instanceof Map<?, ?>)) {
            try {
                Object asMap = native_.getClass().getMethod("asMap").invoke(native_);
                if (asMap instanceof Map<?, ?> map) {
                    native_ = map;
                }
            } catch (ReflectiveOperationException ignored) {
                // Not a Caffeine-shaped cache; fall through to the error below.
            }
        }

        if (!(native_ instanceof Map<?, ?> map)) {
            throw new UnsupportedOperationException(
                    "Cannot evict by tenant from cache '" + cache.getName() + "' (native type "
                    + cache.getNativeCache().getClass().getName() + "). Its keys cannot be "
                    + "enumerated, so this would silently remove nothing. Implement eviction "
                    + "for this provider — for Redis, SCAN for keys matching \"" + tenantId
                    + "::*\" — rather than assuming the entries are gone.");
        }

        int removed = 0;
        for (Object key : Map.copyOf(map).keySet()) {
            if (key instanceof TenantCacheKey tenantKey && tenantKey.tenant().equals(tenantId)) {
                map.remove(key);
                removed++;
            }
        }
        return removed;
    }
}
