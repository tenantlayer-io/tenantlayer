package io.tenantlayer.cache;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;

/**
 * Feature 96 — wraps the application's own {@link CacheManager} so caches are qualified
 * by tenant.
 *
 * <h2>Every cache is tenant-scoped unless you say otherwise</h2>
 *
 * The default is deliberate and is the opposite of convenient. Opting <em>in</em> would
 * mean that forgetting to configure a new cache leaks it across tenants, and the failure
 * would be silent. Opting <em>out</em> means forgetting costs you a cache hit rate on a
 * shared reference table, which someone notices in a dashboard rather than in a breach.
 *
 * <p>Name shared caches explicitly with {@code tenantlayer.cache.shared}.
 */
public class TenantAwareCacheManager implements CacheManager {

    private final CacheManager delegate;
    private final Set<String> sharedCaches;
    private final ConcurrentMap<String, Cache> wrapped = new ConcurrentHashMap<>();

    public TenantAwareCacheManager(CacheManager delegate, Set<String> sharedCaches) {
        this.delegate = delegate;
        this.sharedCaches = sharedCaches == null ? Set.of() : Set.copyOf(sharedCaches);
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(name);
        if (cache == null) {
            return null;
        }
        if (sharedCaches.contains(name)) {
            return cache;
        }
        return wrapped.computeIfAbsent(name, ignored -> new TenantAwareCache(cache));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    public CacheManager delegate() {
        return delegate;
    }

    public Set<String> sharedCaches() {
        return sharedCaches;
    }
}
