package io.tenantlayer.web;

import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantStatus;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Feature 54 — the status lookup {@link TenantFilter} performs on every scoped request,
 * remembered for a short while.
 *
 * <p>This sits in front of {@link TenantRegistry#find} for the enforcement path <em>only</em>.
 * The registry bean itself is not wrapped, on purpose: {@code TenantProvisioning.onboard}
 * reads the same row to decide whether a tenant is already ACTIVE, which is how a retried
 * signup stays idempotent, and {@code forEachTenant} reads it to decide who a job visits.
 * Both are infrequent and want the truth. Enforcement is per-request, hot, and perfectly
 * happy with an answer that is a few seconds stale — so it is the one that gets cached.
 *
 * <p>Two consequences worth knowing. A suspension takes effect on requests within the TTL,
 * not on the very next one. And a registry that cannot answer is asked again on the next
 * request rather than having its failure remembered — a cache must not turn one outage
 * into a longer one, and must not fail open either, so exceptions pass straight through.
 *
 * <p>The tenant id comes from the request, so an attacker can send as many distinct ones as
 * they like. The cache is bounded for that reason: past ten thousand entries it is cleared
 * rather than grown, which degrades to one lookup per request — the behaviour without a
 * cache — instead of to an out-of-memory error.
 */
public final class TenantStatusCache {

    static final int MAX_ENTRIES = 10_000;

    private final TenantRegistry registry;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Absent means the registry has no row for the tenant — a remembered answer too. */
    private record Entry(Optional<TenantStatus> status, long expiresAt) {
    }

    /**
     * @param ttl how long an answer is trusted; {@link Duration#ZERO} (or negative) means
     *            every request asks the registry, and nothing is stored
     */
    public TenantStatusCache(TenantRegistry registry, Duration ttl) {
        this(registry, ttl, System::nanoTime);
    }

    /** Visible so tests can move time forward without sleeping. */
    TenantStatusCache(TenantRegistry registry, Duration ttl, LongSupplier clock) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        this.ttlNanos = ttl.isNegative() ? 0 : ttl.toNanos();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * The tenant's status, or empty when the registry has no row for it.
     *
     * @throws io.tenantlayer.registry.TenantRegistryException if the registry cannot
     *                                                          answer; nothing is cached
     */
    public Optional<TenantStatus> statusOf(String tenantId) {
        if (ttlNanos == 0) {
            return lookup(tenantId);
        }

        long now = clock.getAsLong();
        Entry cached = entries.get(tenantId);
        if (cached != null && now - cached.expiresAt() < 0) {
            return cached.status();
        }

        Optional<TenantStatus> status = lookup(tenantId);
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(tenantId, new Entry(status, now + ttlNanos));
        return status;
    }

    /** Forgets every remembered answer. The next request for any tenant asks the registry. */
    public void clear() {
        entries.clear();
    }

    /** How many answers are currently remembered, expired or not. For tests of the bound. */
    int size() {
        return entries.size();
    }

    private Optional<TenantStatus> lookup(String tenantId) {
        return registry.find(tenantId).map(TenantRegistration::status);
    }
}
