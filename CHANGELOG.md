# Changelog

Notable changes per release. This project follows [semantic versioning](https://semver.org),
with the usual 0.x caveat: breaking changes may land in any 0.x release, and will always be
listed here.

## 0.2.0 — 2026-09-06

### If you use `@Cacheable` on tenant-scoped data, read this first

**0.1.0 did nothing about caching, and its documentation did not mention caching at all.**
A cache hit never reaches the database, so row-level security cannot see it and cannot
prevent it — meaning a cached result for one tenant could be served to another. Nothing in
0.1.0 warned you.

This release closes that. Cache keys are now qualified by tenant automatically.

If you are on 0.1.0, either upgrade, or audit every `@Cacheable` on a tenant-scoped result
and key it by tenant yourself.

**Upgrading invalidates existing cache entries.** Keys change shape, so every entry misses
once and is repopulated. That is the intended behaviour, not a defect.

### Added

- **Tenant-scoped cache keys** (#13). Every cache is tenant-scoped unless named under
  `tenantlayer.cache.shared`. With no tenant bound, reads miss and writes are dropped — the
  underlying method still runs, so behaviour is correct and only slower.
- **Per-tenant cache eviction** (#14). `TenantCacheEvictor.evictTenant(id)` for suspend,
  delete and move. Throws on providers whose keys cannot be enumerated rather than
  silently removing nothing.
- **`TenantConnectionStrategy`** (#18), the seam that decides how a connection is obtained
  and prepared. It owns *acquisition*, not just preparation, because database-per-tenant
  must choose a pool before acquiring anything. Methods added to it in future will be
  `default`.
- **Schema-per-tenant** (#3). `tenantlayer.strategy=SCHEMA_PER_TENANT`, with
  `tenantlayer.schema.prefix`. `search_path` is set on every checkout, never reset on
  return.
- Architecture, adoption and troubleshooting guides, and a rendered architecture diagram.

### Changed

- `TenantAwareDataSource` now delegates to a `TenantConnectionStrategy`. Its existing
  constructor and behaviour are unchanged: `new TenantAwareDataSource(dataSource)` is still
  row-level security on that pool.

### Documented

- **Session-scoped tenants are unsafe under PgBouncer transaction or statement pooling.**
  A server connection is yours for one transaction only, so the setting can outlive your
  use of it and be visible to another client. Session pooling is fine. Transaction-scoped
  binding is tracked as #29.

### Known limitations, unchanged

- Superusers, and table owners without `FORCE ROW LEVEL SECURITY`, bypass policies by
  design.
- Code that unwraps a pooled connection to a raw `PgConnection` is outside the wiring.
- Database-per-tenant routing and per-tenant migrations are not built.

## 0.1.0 — 2026-09-05

First release. Thirty of the thirty-four features on the v0.1 roadmap.

Verified on Java 17 and 21, Hibernate 6.6 and 7.0, Spring Boot 3.5, Postgres 16. Every
isolation claim is mutation-tested — the implementation was broken deliberately and the
test confirmed to fail.

### Isolation

- Leak-proof Postgres RLS wiring: the tenant is set on **every** connection checkout rather
  than reset on return, so a missed reset cannot ride a tenant back into the pool
- No tenant bound resolves to no rows, never all rows
- One-shot RLS policy generation, emitting `FORCE ROW LEVEL SECURITY`, a `nullif` guard and
  the index the predicate needs
- Tenant-scoped entity scanning, with include/exclude overrides
- Hibernate `@TenantId` discriminator strategy

### Resolution and authorisation

- Header, subdomain, path-segment and JWT-claim resolvers, plus a pluggable
  `TenantResolver` SPI
- Ordered resolver chain where order is precedence
- Strict mode: an unresolvable tenant is rejected, never silently defaulted
- Membership verification — the resolved tenant is checked against the authenticated
  principal, so a claimed tenant is not a granted one
- Servlet filter ordering adapts automatically when resolution needs an authenticated
  principal

### Propagation

- `@Async` and Spring task executors
- Virtual threads, including the `SimpleAsyncTaskExecutor` that Boot substitutes when
  `spring.threads.virtual.enabled=true`
- `CompletableFuture` and arbitrary executors via `TenantExecutors`
- `@Scheduled` jobs via `TenantTasks.forEachTenant`, which attempts every tenant and names
  the ones that failed
- Outbound `RestTemplate`, `RestClient`, `WebClient` and Feign
- Kafka produce and consume, including batch listeners, retries and recoverers

### Registry, observability, testing

- Table-backed tenant registry carrying status, region, group, datasource reference and
  metadata
- MDC log enrichment
- `@WithTenant`, `IsolationAssertions.assertTenantCannotSee`, and `TenantPostgres`
  Testcontainers fixtures that hand out a least-privileged connection rather than a
  superuser

### Not included

- **ScopedValue context backing.** The storage SPI ships with the ThreadLocal
  implementation; `ScopedValue` is a preview API until JDK 25 and shipping it would force
  `--enable-preview` on every consumer.
- Schema-per-tenant and database-per-tenant routing (v0.2 / v0.3)
- Reactor context propagation and Spring Batch (v0.2)

### Known limitations

- Code that unwraps a pooled connection to a raw `PgConnection` is outside enforcement from
  that point on
- A superuser, or a table owner without `FORCE ROW LEVEL SECURITY`, bypasses row-level
  security by design — connect as a least-privileged role
