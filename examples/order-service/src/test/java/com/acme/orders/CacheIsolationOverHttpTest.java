package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orders.support.Api;
import com.acme.orders.support.OrdersDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The cache is the one thing that can defeat row-level security, because a cache hit never
 * reaches the database.
 *
 * <p>This test exists here rather than in the library because the library's cache tests
 * construct the tenant-aware manager directly. None of them go through the
 * autoconfiguration, so a {@code BeanPostProcessor} that silently failed to wrap anything
 * would pass every one of them — and every application would then share cached results
 * across tenants while the library's suite stayed green.
 *
 * <p>The cached method takes no arguments, so every tenant asks for an identical cache key.
 * That is the dangerous shape and the common one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CacheIsolationOverHttpTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        OrdersDatabase.bindDataSource(registry);
    }

    @BeforeAll
    static void prepare() {
        OrdersDatabase.reset();
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private CacheManager cacheManager;

    private Api api;

    @BeforeEach
    void setUp() {
        OrdersDatabase.truncate();
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        api = new Api(http);

        api.placeAs("acme", "acme corp", "laptop", 100);
        api.placeAs("globex", "globex ltd", "monitor", 200);
        api.placeAs("globex", "globex ltd", "keyboard", 300);
    }

    private long statsFor(String tenant) {
        JsonNode body = api.getAs("/orders/stats", tenant).getBody();
        return body.get("orderCount").asLong();
    }

    @Test
    @DisplayName("the autoconfiguration actually wraps the application's CacheManager")
    void cacheManagerIsWrapped() {
        assertThat(cacheManager.getClass().getName())
                .as("if this is Boot's plain manager, nothing is qualifying keys and every "
                    + "cached result is shared across tenants")
                .contains("TenantAwareCacheManager");
    }

    @Test
    @DisplayName("a cached result for one tenant is not served to another")
    void cachedResultIsNotSharedAcrossTenants() {
        long acmeFirst = statsFor("acme");
        assertThat(acmeFirst)
                .as("acme placed one order")
                .isEqualTo(1);

        long globex = statsFor("globex");

        assertThat(globex)
                .as("globex asked for the same cache key immediately after acme populated "
                    + "it, and must get its own two orders rather than acme's cached one")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the cache is actually being used, so the test above is not vacuous")
    void theCacheIsWarm() {
        assertThat(statsFor("acme")).isEqualTo(1);

        // Add an order out of band; a cached read must not see it.
        api.placeAs("acme", "acme corp", "mouse", 50);

        assertThat(statsFor("acme"))
                .as("if this is 2, nothing was cached and the isolation test proves nothing")
                .isEqualTo(1);
    }
}
