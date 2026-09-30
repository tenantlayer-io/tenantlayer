# Caching

## Why a cache needs its own answer

A cache is a hole straight through every other isolation layer in this library.

Row-level security is applied by Postgres to statements on a connection. A cache **hit**
never reaches the connection — so the database never sees the read, and no policy can
prevent it. One tenant's result served to another is a cross-tenant read that your most
trusted control cannot help with.

This is the one place where TenantLayer enforces isolation in Java rather than deferring
to the database, because there is nothing else that can.

### The bug, concretely

```java
@Cacheable("orders")
public List<Order> recentOrders() {
    return orders.findRecent();     // correctly scoped by the policy...
}
```

```
acme    GET /orders   → miss → query runs → acme's rows → cached under key "recentOrders"
globex  GET /orders   → HIT  → acme's rows returned
```

The second request never reaches Postgres, so the policy is never consulted. Every other
layer in this library did its job; the answer still crossed tenants.

## What it does

Wrap nothing, configure nothing. If a `CacheManager` exists, TenantLayer wraps it and
qualifies every key with the acting tenant:

```java
@Cacheable("orders")
public List<Order> recentOrders() { ... }
```

acme and globex calling that method now read and write different cache entries, even
though the key is identical.

## Every cache is tenant-scoped unless you say otherwise

That default is deliberately the inconvenient one.

Opting *in* would mean a cache someone forgets to configure leaks across tenants, silently.
Opting *out* means a forgotten cache costs you hit rate on shared reference data — which
someone notices in a dashboard rather than in a breach.

Name your genuinely shared caches:

```properties
tenantlayer.cache.shared=countries,feature-flags,exchange-rates
```

Those are left completely alone: unqualified keys, shared across every tenant, exactly as
before.

## No tenant means no cache

With a tenant-scoped cache and nothing bound, reads miss and writes are dropped. The method
behind the cache still runs, so behaviour stays correct — it is only slower.

The alternative would be to fall back to an unqualified key, which would put an untenanted
result into a namespace every tenant can read. That is precisely the failure this feature
exists to prevent, so it fails closed instead.

## Async methods

`@Cacheable` on a method returning `CompletableFuture` is qualified the same way, with or
without `sync = true`, and follows the same no-tenant rule.

With `sync = true` the method runs with the caller's tenant bound, even when the cache
provider runs it on another thread. Spring's default `ConcurrentMapCache` does exactly that,
and without the binding a tenant-scoped query inside the method would run as no tenant.

Without `sync`, Spring stores the result once the future completes, and that can happen on
the thread that completed it rather than the caller's. The key is qualified with whatever
tenant is bound there: with none, the write is dropped and the next call runs the method
again; with another tenant, the result would be stored as theirs. If your futures can
complete on a thread that is not carrying the caller's tenant, use `sync = true`, which
qualifies the key before the method runs.

## Evicting one tenant

Needed whenever a tenant is suspended, deleted or moved. Without it, suspending a tenant
leaves their data readable from cache until the entries expire, which makes "suspended"
mean rather less than it sounds.

```java
@Autowired TenantCacheEvictor evictor;

int removed = evictor.evictTenant("acme");
```

### What it can and cannot do

Spring's `Cache` interface cannot enumerate keys, so eviction by tenant has to reach the
native cache. Providers whose native cache is a `Map` — the default `ConcurrentMapCache`,
and Caffeine — are handled.

**Anything else throws rather than silently doing nothing.** A no-op eviction is worse than
an error, because you would believe the data was gone.

For Redis, the equivalent is a `SCAN` over the tenant's prefix. Publish a
`TenantCacheEvictor` bean and yours is used instead:

`TenantCacheEvictor` is a class rather than an interface, and it is registered with
`@ConditionalOnMissingBean` — so override `evictTenant` and publish your own:

```java
@Bean
TenantCacheEvictor tenantCacheEvictor(CacheManager cacheManager, StringRedisTemplate redis) {
    return new TenantCacheEvictor(cacheManager) {
        @Override
        public int evictTenant(String tenantId) {
            // Keys carry the tenant, so one prefix covers all of that tenant's entries.
            ScanOptions options = ScanOptions.scanOptions()
                    .match(tenantId + "::*")
                    .count(500)
                    .build();

            int removed = 0;
            try (Cursor<String> keys = redis.scan(options)) {
                while (keys.hasNext()) {
                    redis.delete(keys.next());
                    removed++;
                }
            }
            return removed;
        }
    };
}
```

`SCAN` rather than `KEYS` deliberately — `KEYS` blocks Redis for the duration, which on a
large keyspace takes production down while you suspend one tenant.

## `clear()` still clears everything

`Cache.clear()` and `@CacheEvict(allEntries = true)` clear the whole cache across every
tenant, because that is what their contract says. Quietly narrowing them to the current
tenant would make a documented API silently not do what it says. Use `TenantCacheEvictor`
when you mean one tenant.

## Turning it off

```properties
tenantlayer.cache.enabled=false
```

Only if you are keying by tenant yourself. If you are not, this is the fastest way to
introduce a cross-tenant read into an otherwise correct application.

## Proving it

The test that matters is the one that fails if the qualification is removed:

```java
@Test
void oneTenantsCachedResultIsNotServedToAnother() {
    List<Order> acme = TenantContext.callWithTenant(
            TenantScope.of("acme"), () -> service.recentOrders());
    assertThat(acme).isNotEmpty();

    List<Order> globex = TenantContext.callWithTenant(
            TenantScope.of("globex"), () -> service.recentOrders());

    assertThat(globex)
            .as("globex was served acme's cached result")
            .doesNotContainAnyElementsOf(acme);
}
```

Make it fail first: add `orders` to `tenantlayer.cache.shared` and confirm it goes red. If
it still passes, the cache was never hit and the test proves nothing — which is the usual
reason a cache-isolation test is vacuous.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.cache.enabled` | `true` | Qualify cache keys by tenant |
| `tenantlayer.cache.shared` | *(empty)* | Caches that are not tenant-scoped |
