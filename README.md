# TenantLayer

**The tenant isolation layer for Spring Boot + Postgres.**

Your query has no `WHERE tenant_id`. It returns only your tenant's rows anyway.

```java
@WithTenant("acme")
@Test
void oneTenantCannotSeeAnother() {
    assertTenantCannotSee("globex");
}
```

## What it does

Postgres already has Row-Level Security. We don't reinvent it — the database does the
enforcing, which is exactly why this is trustworthy. What TenantLayer does is everything
around it, which is where every hand-rolled implementation goes wrong.

### Isolation the database enforces

- **Leak-proof connection wiring.** The tenant is published onto every connection handed
  out of the pool. Not *reset on return* — **set on checkout, unconditionally**, because a
  reset that gets skipped once rides a tenant back into the pool and the next borrower
  inherits it. Setting on checkout means a connection can never be *used* carrying a stale
  tenant. It also costs one round trip instead of two.
- **Absence means nothing, not everything.** With no tenant bound, the setting is written
  as the empty string, and the generated policy guards with `nullif(..., '')` so it
  evaluates to NULL and matches no rows. An unauthenticated request reads zero rows, never
  everyone's.
- **Session scope, not `SET LOCAL`.** `SET LOCAL` only survives inside an explicit
  transaction, and plenty of reads run in autocommit.
- **One-shot policy generation.** `RlsPolicyGenerator` emits the SQL for you to review and
  commit as a migration — including the three things hand-written policies forget:
  `FORCE ROW LEVEL SECURITY` (without it the table owner bypasses the policy, and apps very
  often connect as the owner), the `nullif` guard, and an index on the tenant column
  (a policy predicate on an unindexed column turns every read into a sequential scan).
- **Tenant-scoped entity scanning.** Finds which tables are tenant-scoped from Hibernate's
  runtime metamodel — by `@TenantId`, by column convention, with explicit include/exclude
  for the shared reference table that happens to carry a `tenant_id` audit column.
- **Hibernate discriminator strategy.** `@TenantId` support wired for you, so Hibernate adds
  the predicate on reads and stamps the column on writes. With no tenant bound the resolver
  returns the empty string, so work attempted without a tenant does nothing rather than
  touching everyone's rows.

Use both layers. They are not alternatives:

| | Discriminator | Row-level security |
|---|---|---|
| JPA queries | filtered | filtered |
| Native SQL, `JdbcTemplate` | **not filtered** | filtered |
| Bulk `update` / `delete` | partly | filtered |
| A `psql` session on the same credentials | not filtered | filtered |

### Getting the tenant in the first place

- **Four resolvers out of the box** — HTTP header, subdomain, path segment (`/t/{tenant}/…`),
  and a claim from a Spring-Security-validated **JWT**.
- **An ordered chain where order is precedence.** Put the signed claim first and a spoofed
  header is never consulted.
- **A pluggable SPI.** `TenantResolver<S>` is one method. Resolve from an API key, an mTLS
  certificate, a message attribute — define the bean and the autoconfigured chain backs off.
- **Strict mode, on by default.** No resolvable tenant means 400, never a silent default.
  The alternative returns an empty result set, which reads as "no data" and sends the caller
  hunting for a bug in their query rather than their request.
- **Unscoped paths are listed, not guessed** — health checks, `/error`, your login endpoint.
- **The subdomain resolver refuses ambiguity.** `www.app.com` does not become a tenant named
  `www`; a bare base domain does not resolve; a multi-label prefix is refused rather than
  guessed at.
- **Actor and subject are separate from day one.** `TenantScope` carries the subject tenant
  plus an optional actor, group and region — unused in v0.1, present so the MSP and
  residency models are a feature later rather than a breaking change to every resolver.

### Keeping it there

A policy can't help if the tenant never reached the thread that opened the connection. When
propagation fails nothing throws — the work runs with no tenant, the query returns zero
rows, and it surfaces as missing data.

| Boundary | Handled |
|---|---|
| Servlet requests | automatically, with guaranteed unwind in a `finally` |
| `@Async` / Spring `TaskExecutor` | automatically |
| **Virtual threads** (`spring.threads.virtual.enabled`) | automatically |
| `CompletableFuture`, hand-rolled pools | `TenantExecutors` |
| `@Scheduled` jobs | `TenantTasks.forEachTenant(…)` |
| Outbound `RestTemplate` / `RestClient` | automatically |
| Outbound `WebClient` | automatically |
| Outbound **Feign** | automatically |
| **Kafka** produce | automatically, via a record header |
| **Kafka** consume, including batch listeners | automatically |
| MDC / log context | automatically |

Three of those are harder than they look, and each is a bug we found rather than a feature
we imagined:

- **Virtual threads silently drop the tenant.** Setting `spring.threads.virtual.enabled=true`
  makes Boot build a `SimpleAsyncTaskExecutor` instead of a `ThreadPoolTaskExecutor`, and a
  task decorator registered only for the pooled one vanishes with it. One property, widely
  recommended, with no mention of tenancy anywhere near it, and every `@Async` method loses
  its tenant. TenantLayer registers both.
- **A Kafka listener's danger is a *retained* tenant, not a lost one.** Listener containers
  process record after record on one long-lived thread. A record carrying no tenant handled
  as whoever came before it is a cross-tenant **write**, and it would never show up as an
  empty result. The context is cleared around every record, whether the listener returned,
  threw, or went to an error handler — so retries and recoverers unwind too. Batches span
  tenants, so `TenantKafka.runAsRecordTenant(record, …)` scopes each record individually and
  refuses one with no tenant header rather than guessing.
- **`CompletableFuture.supplyAsync(…)` with no executor** runs on the common ForkJoinPool,
  which Spring has never heard of and cannot decorate. `TenantExecutors.supplier(…)` /
  `.callable(…)` / `.runnable(…)` capture it, and `TenantExecutors.wrap(…)` covers a whole
  `Executor` or `ExecutorService` including `submit`, `invokeAll` and `invokeAny`.

Every decorator captures on the **submitting** thread and restores the worker's previous
scope afterwards — pool threads are reused, and a worker that keeps the last task's tenant
is the same leak as a connection that does.

### Refusing to take the caller's word for it

Resolution says which tenant a request *claims*. That is a different question from whether
the caller is *entitled* to it, and shipping only the first is how a tenancy layer ends up
trusting `X-Tenant-ID` from the open internet.

- **Membership verification** checks the resolved tenant against the authenticated
  principal — a token claim listing permitted tenants, or a `TENANT_acme` authority. A token
  for acme asking for globex gets **403**, and the tenant is never bound, so no connection
  ever carries it.
- **Absence of a restriction is not permission.** A token with no tenant claim grants
  nothing. An unauthenticated request is a member of nothing.
- **Filter ordering adapts by itself.** Both features read the `SecurityContext`, so the
  filter moves after Spring Security's chain automatically. Left at its usual near-first
  position it would find an empty context on every request and fall through to the header it
  was added to outrank — and nothing would fail. Isolation would just quietly be back to
  convention.
- **`TenantMembershipVerifier` is one method.** Back it with mTLS, an internal service
  token, or a membership table.

### Knowing who your tenants are

- **A table-backed registry** with status, region, group, a datasource reference and
  arbitrary JSON metadata. Region and group are there from v0.1 deliberately: a registry is
  the hardest table to change once it holds production rows.
- **Deliberately no RLS on it.** It is read *during* resolution, before any tenant is known.
  A policy here would hide the registry from the code whose job is to consult it.
- **Run work for every tenant** — `forEachTenant`, `mapEachTenant`, `runAs`. Suspended
  tenants are skipped. **One tenant's failure does not cancel the rest**: every tenant is
  attempted, and the failures are aggregated into an exception that *names them*, in stable
  order, so the alert says which tenants failed rather than that something did.
- **The scheduler thread is left as it was found.** Schedulers pool threads too.

### Proving it, not claiming it

- **`assertTenantCannotSee(other)`** is two-sided on purpose. "A sees none of B's rows"
  passes trivially against an empty table, so it first proves on a privileged connection
  that B's rows genuinely exist, refuses to run with no tenant bound, and refuses when the
  acting tenant *is* the one you asked about.
- **`@WithTenant("acme")`** on a test or a class.
- **`TenantPostgres`** — Testcontainers fixtures that hand you *two* DataSources, because
  the trap is the connection you test through. Testcontainers gives you a superuser, and
  **superusers bypass RLS outright**, so a suite written against it passes whether or not
  the policy works, including after someone deletes it. You get a least-privileged
  connection for the code under test and a separate privileged one for seeding.
- Helpers to create tenant tables with the policy already correct, or deliberately
  unprotected ones for testing the discriminator on its own.

### What it runs on

Java **17** and **21** · Hibernate **6.6** and **7.0** · Spring Boot **3.5** · Postgres.
One dependency and two lines of configuration to start.

Spring Security, Kafka, WebFlux, Feign and Testcontainers are all **optional** — each
unlocks the features above and none is required. The library starts without any of them,
and that is a tested guarantee, not an intention.

## Ten minutes

```xml
<dependency>
  <groupId>io.tenantlayer</groupId>
  <artifactId>tenantlayer-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```properties
tenantlayer.resolvers=JWT,HEADER
tenantlayer.membership.enabled=true
```

Then generate your policies, apply them as a migration, and connect as a role that is not
the table owner. [Getting started](docs/getting-started.md) is the ten-minute version;
[Row-level security](docs/row-level-security.md) is the part to read before you deploy.

## Documentation

[Getting started](docs/getting-started.md) ·
[Row-level security](docs/row-level-security.md) ·
[Isolation strategies](docs/isolation-strategies.md) ·
[Tenant resolution](docs/tenant-resolution.md) ·
[Securing resolution](docs/securing-resolution.md) ·
[Context propagation](docs/context-propagation.md) ·
[Tenant registry](docs/tenant-registry.md) ·
[Testing](docs/testing.md) ·
[Configuration](docs/configuration.md)

## Status

**Pre-alpha. The code is v0.1-complete; the release is not.**

Of the 34 features in the v0.1 roadmap, **30 are built and tested**, and every isolation
claim among them has been mutation-tested — broken deliberately to confirm the test goes
red. The other four are honest about what they are:

| | Status |
|---|---|
| ThreadLocal + **ScopedValue** backing | The storage SPI ships with the ThreadLocal implementation. ScopedValue is a preview API until JDK 25 and shipping it would force `--enable-preview` on every consumer. See [Context storage](docs/context-storage.md). |
| Apache 2.0 core **on Maven Central** | Nothing is published yet. The build produces signed sources and javadoc jars under `-Prelease`; the deploy has not happened. |
| **Docs site** & guides | The guides are written and in `docs/`. There is no hosted site. |
| **Community support** | Issue and PR templates exist. There is no public repository yet, so there is nowhere to file one. |

Everything below is exercised by the suite.

| | Proves |
|---|---|
| `IsolationTest` | The ORM emits no tenant predicate; rows are scoped anyway |
| `PooledConnectionTest` | A recycled connection fails closed, not open |
| `AsyncPropagationTest` | The tenant survives a thread boundary |
| `MembershipVerificationTest` | A token for acme cannot act as globex by setting a header |
| `DiscriminatorStrategyTest` | `@TenantId` scopes a table Postgres is *not* protecting |
| `SchemaGenerationTest` | The generated policy, applied, actually isolates |
| `KafkaPropagationTest` | The tenant survives a broker, and does not linger on the listener |
| `VirtualThreadPropagationTest` | Enabling virtual threads does not drop the tenant |
| `ShippedFixtureTest` | The published test fixtures work outside this repo |

Verified on Java 17 and 21, Hibernate 6.6 and 7.0, Spring Boot 3.5.

## A worked example

[`examples/order-service`](examples/order-service) is an ordinary business service that
consumes TenantLayer as a **published dependency**, not as source — it exists to answer one
question: does this work for somebody who is not TenantLayer?

Grep it for tenancy and there is none. `OrderController` takes no tenant parameter and
reads no header; `OrderRepository` is an empty `JpaRepository` with no `findByTenantId`.
The SQL Hibernate emits has no tenant predicate in it. Isolation holds anyway.

It also earns its keep as a test: it caught a `NoClassDefFoundError` that would have broken
startup for most adopters and that this repo's own 100+ tests structurally could not find,
because a library's optional dependencies are always present on its own test classpath.

## Building

```
export JAVA_HOME=/path/to/jdk-17

mvn test                    # the library, Hibernate 6
mvn test -Phibernate7       # the library, Hibernate 7 + Jakarta Persistence 3.2

mvn install -DskipTests     # then the example, which consumes the artifact
cd examples/order-service && mvn test
```

On JDK 21+ the `jdk21` profile activates automatically and adds the virtual-thread tests.
Tests need Docker — Testcontainers starts Postgres, and Kafka for the messaging tests.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md). One rule matters more than the rest: every isolation
claim is mutation-tested. Break it, watch it fail, then fix it.

## Licence

Apache 2.0.
