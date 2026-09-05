# Changelog

Notable changes per release. This project follows [semantic versioning](https://semver.org),
with the usual 0.x caveat: breaking changes may land in any 0.x release, and will always be
listed here.

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
