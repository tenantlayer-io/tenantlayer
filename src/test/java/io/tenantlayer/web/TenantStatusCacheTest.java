package io.tenantlayer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantRegistryException;
import io.tenantlayer.registry.TenantStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature 54 — the status cache in front of the filter's registry lookup.
 *
 * <p>Time is a fake clock so the TTL can be crossed without sleeping. The registry counts
 * calls so "cached" is asserted, not assumed.
 */
class TenantStatusCacheTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final AtomicLong nanos = new AtomicLong();
    private final CountingRegistry registry = new CountingRegistry();
    private final TenantStatusCache cache = new TenantStatusCache(registry, TTL, nanos::get);

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    @DisplayName("within the TTL the registry is asked once")
    void asksOnceWithinTtl() {
        registry.save(TenantRegistration.of("acme"));

        for (int i = 0; i < 5; i++) {
            assertThat(cache.statusOf("acme")).contains(TenantStatus.ACTIVE);
        }

        assertThat(registry.finds).as("five requests, one lookup").isEqualTo(1);
    }

    @Test
    @DisplayName("a suspension is seen once the TTL has passed, not before")
    void suspensionIsSeenAfterTtl() {
        registry.save(TenantRegistration.of("acme"));
        assertThat(cache.statusOf("acme")).contains(TenantStatus.ACTIVE);

        registry.save(suspended("acme"));

        advance(TTL.minusSeconds(1));
        assertThat(cache.statusOf("acme"))
                .as("inside the window the old answer stands — this is the documented trade-off")
                .contains(TenantStatus.ACTIVE);

        advance(Duration.ofSeconds(2));
        assertThat(cache.statusOf("acme"))
                .as("past the window the registry is asked again and the suspension lands")
                .contains(TenantStatus.SUSPENDED);
        assertThat(registry.finds).isEqualTo(2);
    }

    @Test
    @DisplayName("a tenant the registry does not know is remembered as unknown")
    void unknownIsCachedToo() {
        for (int i = 0; i < 3; i++) {
            assertThat(cache.statusOf("nobody")).isEmpty();
        }

        assertThat(registry.finds)
                .as("an empty registry must not turn every request into a lookup")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a registry failure propagates and is not remembered")
    void failureIsNeitherSwallowedNorCached() {
        registry.save(TenantRegistration.of("acme"));
        registry.failNext = true;

        assertThatThrownBy(() -> cache.statusOf("acme"))
                .as("cannot answer must not become 'served'")
                .isInstanceOf(TenantRegistryException.class);

        assertThat(cache.statusOf("acme"))
                .as("the next request asks again rather than replaying the failure")
                .contains(TenantStatus.ACTIVE);
        assertThat(registry.finds).isEqualTo(2);
    }

    @Test
    @DisplayName("a zero TTL asks the registry every time and stores nothing")
    void zeroTtlIsPassThrough() {
        TenantStatusCache uncached = new TenantStatusCache(registry, Duration.ZERO, nanos::get);
        registry.save(TenantRegistration.of("acme"));

        uncached.statusOf("acme");
        registry.save(suspended("acme"));

        assertThat(uncached.statusOf("acme")).contains(TenantStatus.SUSPENDED);
        assertThat(registry.finds).isEqualTo(2);
    }

    @Test
    @DisplayName("clear() forgets every answer")
    void clearForgets() {
        registry.save(TenantRegistration.of("acme"));
        cache.statusOf("acme");
        registry.save(suspended("acme"));

        cache.clear();

        assertThat(cache.statusOf("acme")).contains(TenantStatus.SUSPENDED);
    }

    @Test
    @DisplayName("the cache is bounded — request-supplied ids cannot grow it without limit")
    void boundedByDistinctIds() {
        registry.save(TenantRegistration.of("acme"));
        cache.statusOf("acme");

        int flood = TenantStatusCache.MAX_ENTRIES + 5;
        for (int i = 0; i < flood; i++) {
            cache.statusOf("attacker-" + i);
        }

        assertThat(cache.size())
                .as("an unbounded map would hold every id it was ever sent")
                .isLessThanOrEqualTo(TenantStatusCache.MAX_ENTRIES);

        // Overflow clears and carries on caching: the most recent id is a hit, not a lookup.
        int findsAfterFlood = registry.finds;
        cache.statusOf("attacker-" + (flood - 1));
        assertThat(registry.finds)
                .as("the cache still works after the flood; it did not degrade to pass-through")
                .isEqualTo(findsAfterFlood);

        // acme was evicted by the flood, so it costs one more lookup — the price of overflow
        // is a miss that would have been a hit, which is the behaviour without a cache.
        assertThat(cache.statusOf("acme")).contains(TenantStatus.ACTIVE);
        assertThat(registry.finds).isEqualTo(findsAfterFlood + 1);
    }

    private static TenantRegistration suspended(String tenantId) {
        return new TenantRegistration(tenantId, TenantStatus.SUSPENDED, null, null, null, Map.of());
    }

    static class CountingRegistry implements TenantRegistry {

        private final Map<String, TenantRegistration> rows = new TreeMap<>();
        int finds;
        boolean failNext;

        @Override
        public Optional<TenantRegistration> find(String tenantId) {
            finds++;
            if (failNext) {
                failNext = false;
                throw new TenantRegistryException("registry unavailable", null);
            }
            return Optional.ofNullable(rows.get(tenantId));
        }

        @Override
        public List<TenantRegistration> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public List<String> activeTenantIds() {
            return rows.values().stream()
                    .filter(TenantRegistration::isActive)
                    .map(TenantRegistration::tenantId)
                    .toList();
        }

        @Override
        public void save(TenantRegistration registration) {
            rows.put(registration.tenantId(), registration);
        }

        @Override
        public boolean delete(String tenantId) {
            return rows.remove(tenantId) != null;
        }
    }
}
