# Changelog

Notable changes per release. This project follows [semantic versioning](https://semver.org),
with the usual 0.x caveat: breaking changes may land in any 0.x release, and will always be
listed here.

## Unreleased

### Suspended tenants are refused, not served

The registry's `status` column has existed since 0.1.0 and, by its own javadoc, was "carried
and reported, not yet used to reject requests". It is now used. When a `TenantRegistry` bean
exists, `TenantFilter` looks the resolved tenant up after membership verification and before
the tenant is bound, and refuses any status other than `ACTIVE` with a 403. This is the same
rule `forEachTenant` has always applied, so a suspended tenant is off for its users and for
the nightly job at the same moment.

A tenant the registry does not contain is **not** refused. Existence is a different control
from status, and turning it on would make every deployment with an empty registry reject all
traffic. Nothing changes for a deployment without a registry bean.

**Behaviour change.** The registry is autoconfigured whenever a DataSource exists, so with
this release the request path reads it. If your application has a DataSource but never
created the `tenantlayer_tenants` table, requests will now fail with a registry error rather
than be served — a failure that is loud on purpose. Create the table (the DDL is in
`TenantRegistrySchema.DDL`), or set `tenantlayer.registry.enforce-status=false` to keep the
registry for iteration and provisioning without the check on the request path.

**The status lookup is cached, thirty seconds by default.** Enforcement runs on every scoped
request and is happy with an answer a few seconds old, so the filter remembers each tenant's
status for `tenantlayer.registry.status-cache-ttl` (`0s` disables the cache). The consequence:
a suspension takes effect on requests within the TTL, not on the very next one. Only the
filter's lookup is cached — `TenantRegistry.find()` is untouched, because `TenantProvisioning`
reads it to decide whether a tenant is already ACTIVE, and a stale answer there would re-run
every provisioning hook on a retried signup. Provisioning and `forEachTenant` are infrequent
and want the truth; enforcement is hot and wants speed. Cache the one, leave the other alone.

**Upgrading: your application role needs `select` on the registry.**

```sql
grant select on tenantlayer_tenants to <your application role>;
```

The table existing is not sufficient — the role reading it has to be allowed to. This
catches more people than the missing table does, because the documented setup is a role
that is neither superuser nor table owner, and such a role has no implicit access to a
table it does not own. Without the grant, every scoped request fails with `permission
denied for table tenantlayer_tenants`. The example's own `OrderIsolationTest` was written
that way and started failing on exactly this, which is how it was found.

`TenantFilter` gained two constructors: one taking the registry (status checked on every
request), one taking the registry and a cache TTL. The existing constructors still compile
and behave as before. The membership-verifier recipe that checked status by hand is no longer
needed and has been removed from the docs.

## 0.4.0 — 2026-09-10

### The control plane seams

Four features that together make a tenant's whole lifecycle something you can drive, rather
than something you assemble by hand each time.

**Onboarding.** `TenantProvisioning.onboard(tenantId)` does the sequence — registry row,
migrations, hooks, then ACTIVE — as one call from your own signup path. Previously every
adopter wrote that orchestration themselves, and the step people got wrong was seeding:
rows written with no tenant bound fail the policy under row-level security and have no
connection at all under database-per-tenant. Hooks now run with the new tenant bound, so a
`TenantProvisioningHook` writes seed data with ordinary repository calls.

**Provisioning hooks.** `TenantProvisioningHook` is where everything application-specific
goes — seed data, a Stripe customer, a search index, a warmed cache. Ordered, and a hook
that throws stops provisioning rather than leaving a tenant that looks ready.

**Tenant endpoints.** `/actuator/tenants` makes the registry operable over HTTP: list, read,
onboard, change status, remove. Off unless both enabled and exposed, and it inherits
whatever protects your other management endpoints. Creating goes through provisioning, not
a bare registry insert.

**The isolation checker.** At start-up, compares what your entities say should be protected
against what Postgres actually enforces, and logs the difference: tables with no row-level
security, tables with it enabled but no policy, a missing FORCE, and the case that makes all
of those moot — connecting as a superuser. It warns and never fails start-up.

**Tenant tag on metrics.** Every observation carries the acting tenant, behind a hard
cardinality cap. The cap is the feature: an unbounded tenant tag multiplies your time series
by your tenant count, and the first anyone hears of it is a Prometheus that will not start.

### Also

**Auth0 and Keycloak resolver presets**, contributed by @Zoymusk — `AuthPresets.forAuth0()`
and `KeycloakOrganizationClaimResolver`. The Keycloak one refuses to guess when a token
carries more than one organization in its map form, because iteration order there is not
token order.

**The guides were rewritten**, from roughly 9,000 words to 18,000 across 20 pages, with
worked examples throughout. New: recipes, onboarding, async and threads, outbound HTTP,
Kafka, metrics, the isolation checker, and tenant endpoints.

### One thing to check when upgrading

`TenantStatus` has a third constant, `PROVISIONING`. A **switch expression** over that enum
with no `default` will no longer compile, since switch expressions must be exhaustive — which
is the compiler telling you about a state you now have to think about. A switch *statement*
is unaffected and will simply fall through, which is the quieter and more dangerous of the
two, so it is worth grepping for. A tenant that is `PROVISIONING` is not
servable; `TenantStatus.isServable()` is there to ask directly rather than comparing to
`ACTIVE` by hand.

Nothing else changes. Every existing property and interface behaves as before, and the
methods added to `TenantConnectionStrategy` are `default`.

## 0.3.0 — 2026-09-08

### Database-per-tenant

`tenantlayer.strategy=DATABASE_PER_TENANT` gives every tenant its own database behind its
own pool, completing the three canonical strategies. Databases are declared under
`tenantlayer.databases.<ref>`, keyed by the tenant's `datasource_ref` so tenants can share a
shard; pools open on first use and are capped by `tenantlayer.databases-max-pools`.
Publishing a `TenantDataSourceProvider` bean replaces the configuration entirely, for
deployments whose connection details come from a secrets manager.

An unrecognised tenant, or none, throws before a connection exists — there is deliberately
no fall back to the application's datasource.

**This strategy requires `spring.jpa.database-platform` to be set.** Hibernate determines
its dialect at start-up by asking a connection for metadata, and at start-up no tenant is
bound, so there is no database to ask. Without it the application fails to start with an
error about dialects that says nothing about tenancy.

### Fixed: migrations under a per-database strategy

`TenantMigrationRunner` decided between migrating once and migrating per tenant by asking
whether the strategy gave each tenant its own *schema*. Database-per-tenant gives each
tenant its own *database* while sharing a schema name, so that test would have migrated one
database and silently left every other tenant on an old version. The runner now asks
`TenantConnectionStrategy.migratesPerTenant()` and runs against each tenant's own datasource.

Two `default` methods were added to `TenantConnectionStrategy` — `migratesPerTenant()` and
`dataSourceFor(String)`. Existing implementations keep compiling and behave exactly as before.

### Fixed: the tenant registry no longer routes through the tenant-aware datasource

`TenantRegistryAutoConfiguration` handed `JdbcTenantRegistry` the wrapped datasource, so
registry reads were routed by whichever tenant happened to be bound. The registry answers
*which tenants exist* — a question asked before any tenant is known — so routing it by the
acting tenant was always a contradiction. Under row-level security it happened to work,
which is why it went unnoticed; under database-per-tenant it throws.

The registry now reads the unwrapped datasource. **This affects every strategy, not only
the new one.** If you relied on the registry being tenant-routed, you were relying on a bug;
if you use a single database, nothing changes for you.

`TenantAwareDataSource.unwrap(DataSource)` is now public, since both the registry and the
migration runner need it.

### Upgrading from 0.2.0

Nothing is required. Every existing property, strategy and interface behaves as before, and
the two new interface methods are `default`.

If you adopt `DATABASE_PER_TENANT`, set `spring.jpa.database-platform` — see above — and
note that this strategy fails **louder** than the others when no tenant is bound: row-level
security returns an empty result set, schema-per-tenant raises an unresolved relation, and
this throws before a connection exists. An application that quietly copes with empty results
will start failing visibly. That is a property of the switch, not a regression.

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

- **Transaction-scoped RLS binding** (#29). The opt-in
  `ROW_LEVEL_SECURITY_TRANSACTION_SCOPED` strategy uses `SET LOCAL` at the Spring transaction
  boundary for PgBouncer transaction pooling. It uses a connection-only lifecycle wrapper and
  Spring's transaction execution listener, preserving normal metadata, vendor `unwrap`, and
  statement/result-set behavior. Tenant-scoped statements outside an active transaction fail
  closed, while the shared registry remains readable before a tenant exists. The existing
  session-scoped default is unchanged.
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
