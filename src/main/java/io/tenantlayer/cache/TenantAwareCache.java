package io.tenantlayer.cache;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

/**
 * Feature 96 — qualifies every key with the acting tenant.
 *
 * <h2>Why a cache needs its own answer</h2>
 *
 * A cache is a hole straight through every other isolation layer in this library. Row-level
 * security is applied by Postgres to statements on a connection; a cache <em>hit</em> never
 * reaches the connection, so no policy can help. One tenant's result served to another is a
 * cross-tenant read that the database never sees and never gets a chance to prevent.
 *
 * <h2>No tenant means no cache</h2>
 *
 * When the cache is tenant-scoped and nothing is bound, reads miss and writes are dropped.
 * The method behind the cache still runs, so behaviour is correct — it is only slower. The
 * alternative, falling back to an unqualified key, would put an untenanted result into a
 * namespace every tenant can read, which is exactly the failure this class exists to stop.
 */
public class TenantAwareCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareCache.class);

    private final Cache delegate;

    public TenantAwareCache(Cache delegate) {
        this.delegate = delegate;
    }

    /** @return the qualified key, or {@code null} when there is no tenant to qualify it with. */
    @Nullable
    private Object qualify(Object key) {
        String tenant = TenantContext.current().map(TenantScope::subject).orElse(null);
        if (tenant == null) {
            log.debug("cache '{}' accessed with no tenant bound; bypassing", delegate.getName());
            return null;
        }
        return new TenantCacheKey(tenant, key);
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        Object qualified = qualify(key);
        return qualified == null ? null : delegate.get(qualified);
    }

    @Override
    @Nullable
    public <T> T get(Object key, @Nullable Class<T> type) {
        Object qualified = qualify(key);
        return qualified == null ? null : delegate.get(qualified, type);
    }

    /**
     * With no tenant, the loader still runs but its result is not stored. Correct, and
     * slower — which is the right way round.
     */
    @Override
    @Nullable
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object qualified = qualify(key);
        if (qualified == null) {
            try {
                return valueLoader.call();
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }
        }
        return delegate.get(qualified, valueLoader);
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        Object qualified = qualify(key);
        if (qualified != null) {
            delegate.put(qualified, value);
        }
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        Object qualified = qualify(key);
        return qualified == null ? null : delegate.putIfAbsent(qualified, value);
    }

    @Override
    public void evict(Object key) {
        Object qualified = qualify(key);
        if (qualified != null) {
            delegate.evict(qualified);
        }
    }

    @Override
    public boolean evictIfPresent(Object key) {
        Object qualified = qualify(key);
        return qualified != null && delegate.evictIfPresent(qualified);
    }

    /**
     * Clears the whole cache, across every tenant — because that is what the contract of
     * {@code Cache.clear()} says, and quietly narrowing it to the current tenant would make
     * {@code @CacheEvict(allEntries = true)} silently not do what it says.
     *
     * <p>To clear one tenant, use {@link TenantCacheEvictor}.
     */
    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    public Cache delegate() {
        return delegate;
    }
}
