# Caching

## Why a cache needs its own answer

A cache is a hole straight through every other isolation layer in this library.

Row-level security is applied by Postgres to statements on a connection. A cache **hit**
never reaches the connection — so the database never sees the read, and no policy can
prevent it. One tenant's result served to another is a cross-tenant read that your most
trusted control cannot help with.

This is the one place where TenantLayer enforces isolation in Java rather than deferring
to the database, because there is nothing else that can.

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

**Anything else throws rather than silently doing nothing.** For Redis, the equivalent is a
`SCAN` over `tenant::*` and you should implement it. A no-op eviction is worse than an
error, because you would believe the data was gone.

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

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.cache.enabled` | `true` | Qualify cache keys by tenant |
| `tenantlayer.cache.shared` | *(empty)* | Caches that are not tenant-scoped |
