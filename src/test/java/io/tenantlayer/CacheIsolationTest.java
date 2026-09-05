package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.cache.TenantAwareCacheManager;
import io.tenantlayer.cache.TenantCacheEvictor;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/**
 * Features 96 and 97 — the hole a cache punches through every other isolation layer.
 *
 * A cache hit never reaches the database, so row-level security cannot see it and cannot
 * help. That makes this the one place in the library where isolation is enforced in Java,
 * and the only place where a test failing here means a genuine cross-tenant read rather
 * than an empty result.
 */
class CacheIsolationTest {

    private CacheManager manager;

    @BeforeEach
    void setUp() {
        manager = new TenantAwareCacheManager(
                new ConcurrentMapCacheManager("orders", "countries"), Set.of("countries"));
        TenantContext.clear();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Cache orders() {
        return manager.getCache("orders");
    }

    @Test
    @DisplayName("one tenant cannot read another tenant's cached value")
    void tenantsDoNotShareCachedValues() {
        TenantContext.runWithTenant(TenantScope.of("acme"), () -> orders().put(1L, "acme order"));

        String asAcme = TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> orders().get(1L, String.class));
        String asGlobex = TenantContext.callWithTenant(TenantScope.of("globex"),
                () -> orders().get(1L, String.class));

        assertThat(asAcme)
                .as("acme must still get its own cached value, or this test is vacuous")
                .isEqualTo("acme order");
        assertThat(asGlobex)
                .as("globex asked for the same cache key and must not receive acme's value")
                .isNull();
    }

    @Test
    @DisplayName("the same key for two tenants holds two different values")
    void sameKeyDifferentValues() {
        TenantContext.runWithTenant(TenantScope.of("acme"), () -> orders().put(1L, "acme"));
        TenantContext.runWithTenant(TenantScope.of("globex"), () -> orders().put(1L, "globex"));

        assertThat(TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> orders().get(1L, String.class))).isEqualTo("acme");
        assertThat(TenantContext.callWithTenant(TenantScope.of("globex"),
                () -> orders().get(1L, String.class))).isEqualTo("globex");
    }

    @Test
    @DisplayName("with no tenant bound the cache is bypassed, not shared")
    void noTenantBypassesTheCache() {
        TenantContext.runWithTenant(TenantScope.of("acme"), () -> orders().put(1L, "acme order"));

        assertThat(orders().get(1L))
                .as("an untenanted read must not reach into any tenant's namespace")
                .isNull();

        orders().put(2L, "written with no tenant");
        assertThat(TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> orders().get(2L, String.class)))
                .as("an untenanted write must not land somewhere a tenant can read it")
                .isNull();
    }

    @Test
    @DisplayName("the value loader still runs with no tenant — correct, just not cached")
    void loaderStillRunsWithoutTenant() {
        AtomicInteger calls = new AtomicInteger();

        String first = orders().get(9L, () -> { calls.incrementAndGet(); return "computed"; });
        String second = orders().get(9L, () -> { calls.incrementAndGet(); return "computed"; });

        assertThat(first).isEqualTo("computed");
        assertThat(second).isEqualTo("computed");
        assertThat(calls.get())
                .as("behaviour stays correct without a tenant; only the caching is skipped")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a cache named as shared is left alone")
    void sharedCachesAreNotQualified() {
        Cache countries = manager.getCache("countries");
        TenantContext.runWithTenant(TenantScope.of("acme"), () -> countries.put("IN", "India"));

        assertThat(TenantContext.callWithTenant(TenantScope.of("globex"),
                () -> countries.get("IN", String.class)))
                .as("shared reference data is meant to be shared")
                .isEqualTo("India");
    }

    @Test
    @DisplayName("caches are tenant-scoped unless named as shared")
    void defaultIsTenantScoped() {
        CacheManager unconfigured = new TenantAwareCacheManager(
                new ConcurrentMapCacheManager("anything"), Set.of());

        TenantContext.runWithTenant(TenantScope.of("acme"),
                () -> unconfigured.getCache("anything").put("k", "acme value"));

        assertThat(TenantContext.callWithTenant(TenantScope.of("globex"),
                () -> unconfigured.getCache("anything").get("k", String.class)))
                .as("forgetting to configure a cache must fail closed, not leak")
                .isNull();
    }

    @Test
    @DisplayName("evicting one tenant leaves the others intact")
    void evictionIsPerTenant() {
        TenantContext.runWithTenant(TenantScope.of("acme"), () -> orders().put(1L, "acme"));
        TenantContext.runWithTenant(TenantScope.of("globex"), () -> orders().put(1L, "globex"));

        int removed = new TenantCacheEvictor(manager).evictTenant("acme");

        assertThat(removed).isEqualTo(1);
        assertThat(TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> orders().get(1L, String.class))).isNull();
        assertThat(TenantContext.callWithTenant(TenantScope.of("globex"),
                () -> orders().get(1L, String.class)))
                .as("evicting acme must not touch globex")
                .isEqualTo("globex");
    }

    @Test
    @DisplayName("eviction refuses rather than silently removing nothing")
    void evictionFailsLoudlyOnUnsupportedProviders() {
        CacheManager opaque = new TenantAwareCacheManager(new OpaqueCacheManager(), Set.of());

        assertThatThrownBy(() -> new TenantCacheEvictor(opaque).evictTenant("acme"))
                .as("believing data was evicted when it was not is worse than an error")
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("silently remove nothing");
    }

    /** A cache whose native form cannot be enumerated, like Redis. */
    private static final class OpaqueCacheManager implements CacheManager {
        private final Cache cache = new org.springframework.cache.support.NoOpCache("opaque") {
            @Override
            public Object getNativeCache() {
                return "not-a-map";
            }
        };

        @Override
        public Cache getCache(String name) {
            return cache;
        }

        @Override
        public java.util.Collection<String> getCacheNames() {
            return java.util.List.of("opaque");
        }
    }
}
