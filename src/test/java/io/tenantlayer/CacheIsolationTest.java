package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.autoconfigure.TenantCacheAutoConfiguration;
import io.tenantlayer.cache.TenantAwareCacheManager;
import io.tenantlayer.cache.TenantCacheEvictor;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

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

    /** The real wiring: Boot's default cache, wrapped by TenantLayer's post-processor. */
    private final ApplicationContextRunner asyncCaching = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CacheAutoConfiguration.class, TenantCacheAutoConfiguration.class))
            .withUserConfiguration(InvoiceConfig.class)
            .withPropertyValues("spring.cache.type=simple");

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

    @Test
    @DisplayName("async reads are qualified too — one tenant cannot read another's value")
    void asyncTenantsDoNotShareCachedValues() {
        TenantContext.runWithTenant(TenantScope.of("acme"),
                () -> orders().retrieve(1L, () -> CompletableFuture.completedFuture("acme order")).join());

        Object asAcme = TenantContext.callWithTenant(TenantScope.of("acme"), () -> retrieved(1L));
        Object asGlobex = TenantContext.callWithTenant(TenantScope.of("globex"), () -> retrieved(1L));
        String loadedByGlobex = TenantContext.callWithTenant(TenantScope.of("globex"),
                () -> orders().retrieve(1L, () -> CompletableFuture.completedFuture("globex order")).join());

        assertThat(asAcme)
                .as("acme must still get its own cached value, or this test is vacuous")
                .isEqualTo("acme order");
        assertThat(asGlobex)
                .as("globex asked for the same cache key and must not receive acme's value")
                .isNull();
        assertThat(loadedByGlobex)
                .as("globex's read-through must load its own value, not be handed acme's")
                .isEqualTo("globex order");
    }

    @Test
    @DisplayName("async with no tenant bound: reads miss, and the loader runs uncached")
    void asyncWithNoTenantBypassesTheCache() {
        TenantContext.runWithTenant(TenantScope.of("acme"),
                () -> orders().retrieve(1L, () -> CompletableFuture.completedFuture("acme order")).join());
        AtomicInteger calls = new AtomicInteger();
        Supplier<CompletableFuture<String>> loader = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("computed");
        };

        assertThat(orders().retrieve(1L))
                .as("an untenanted async read must not reach into any tenant's namespace")
                .isNull();
        assertThat(orders().retrieve(9L, loader).join()).isEqualTo("computed");
        assertThat(orders().retrieve(9L, loader).join()).isEqualTo("computed");
        assertThat(calls.get())
                .as("behaviour stays correct without a tenant; only the caching is skipped")
                .isEqualTo(2);
        assertThat(TenantContext.callWithTenant(TenantScope.of("acme"), () -> retrieved(9L)))
                .as("an untenanted async write must not land somewhere a tenant can read it")
                .isNull();
    }

    @Test
    @DisplayName("with no tenant, a failing loader fails the future rather than throwing")
    void asyncLoaderFailureWithNoTenantIsReportedThroughTheFuture() {
        IllegalStateException failure = new IllegalStateException("loader failed");

        CompletableFuture<String> result = orders().retrieve(9L, () -> {
            throw failure;
        });

        assertThat(result)
                .as("thrown instead, Spring treats it as a cache error and may run the method twice")
                .isCompletedExceptionally();
        assertThatThrownBy(result::join).hasCause(failure);
    }

    @Test
    @DisplayName("@Cacheable on a CompletableFuture method caches per tenant")
    void asyncCacheableIsPerTenant() {
        asyncCaching.run(context -> {
            Invoices invoices = context.getBean(Invoices.class);

            String acme = TenantContext.callWithTenant(TenantScope.of("acme"), () -> invoices.find(1L).join());
            String acmeAgain = TenantContext.callWithTenant(TenantScope.of("acme"), () -> invoices.find(1L).join());
            String globex = TenantContext.callWithTenant(TenantScope.of("globex"), () -> invoices.find(1L).join());

            assertThat(acme).isEqualTo("acme invoice 1, load 1");
            assertThat(acmeAgain)
                    .as("acme's second call must be served its own cached value")
                    .isEqualTo(acme);
            assertThat(globex)
                    .as("globex asked for the same key and must not be served acme's value")
                    .isEqualTo("globex invoice 1, load 2");
        });
    }

    @Test
    @DisplayName("@Cacheable(sync = true) on a CompletableFuture method caches per tenant, as the caller")
    void syncAsyncCacheableIsPerTenantAndLoadsAsTheCaller() {
        asyncCaching.run(context -> {
            Invoices invoices = context.getBean(Invoices.class);

            String acme = TenantContext.callWithTenant(TenantScope.of("acme"), () -> invoices.findSync(1L).join());
            String acmeAgain = TenantContext.callWithTenant(TenantScope.of("acme"), () -> invoices.findSync(1L).join());
            String globex = TenantContext.callWithTenant(TenantScope.of("globex"), () -> invoices.findSync(1L).join());

            assertThat(acme)
                    .as("ConcurrentMapCache runs the loader on the common pool; it must still run as acme")
                    .isEqualTo("acme invoice 1, load 1");
            assertThat(acmeAgain)
                    .as("acme's second call must be served its own cached value")
                    .isEqualTo(acme);
            assertThat(globex)
                    .as("globex asked for the same key and must not be served acme's value")
                    .isEqualTo("globex invoice 1, load 2");
        });
    }

    /** What {@code retrieve(key)} holds, unwrapped; {@code null} on a miss. */
    @Nullable
    private Object retrieved(Object key) {
        CompletableFuture<?> future = orders().retrieve(key);
        if (future == null) {
            return null;
        }
        Object value = future.join();
        return value instanceof Cache.ValueWrapper wrapper ? wrapper.get() : value;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class InvoiceConfig {
        @Bean
        Invoices invoices() {
            return new Invoices();
        }
    }

    /** Each load records the tenant it ran as, so a value served to the wrong tenant shows. */
    static class Invoices {
        private final AtomicInteger loads = new AtomicInteger();

        @Cacheable("invoices")
        public CompletableFuture<String> find(long id) {
            return CompletableFuture.completedFuture(load(id));
        }

        @Cacheable(cacheNames = "receipts", sync = true)
        public CompletableFuture<String> findSync(long id) {
            return CompletableFuture.completedFuture(load(id));
        }

        private String load(long id) {
            String tenant = TenantContext.current().map(TenantScope::subject).orElse("no tenant");
            return tenant + " invoice " + id + ", load " + loads.incrementAndGet();
        }
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
