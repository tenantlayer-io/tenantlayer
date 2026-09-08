# TenantLayer documentation

Multi-tenancy for Spring Boot and Postgres, where the database does the enforcing.

## What it looks like

Add the dependency, tell it where the tenant comes from, and write your application as if
it had one customer:

```properties
tenantlayer.resolvers=JWT
tenantlayer.strategy=ROW_LEVEL_SECURITY
```

```java
@RestController
class OrderController {

    private final OrderRepository orders;

    @GetMapping("/orders")
    List<Order> list() {
        return orders.findAll();     // returns only the acting tenant's rows
    }
}
```

There is no `where tenantId = ?`, no filter to remember, and nothing in that controller
that mentions tenancy. `findAll()` genuinely means *all* — and Postgres returns only the
rows this connection is allowed to see, because the tenant was published onto it before
your query ran.

The same is true of a native query, a `JdbcTemplate` call, a bulk update, or a psql session
on the same credentials. That is the point of enforcing in the database rather than in the
ORM.

## Where to start

**Setting up a new application** — [Getting started](getting-started.md), then
[Row-level security](row-level-security.md) before you deploy anything. Twenty minutes.

**Building something specific** — [Recipes](recipes.md) has complete, working answers to
onboarding a tenant, suspending one, running a nightly job across all of them, serving an
admin endpoint that spans tenants, and the rest.

**Adding it to a running system** — [Adopting in an existing app](adopting-in-an-existing-app.md).
It is designed to go on gradually rather than as a flag day.

**Evaluating whether to use it** — [Architecture](architecture.md) for how it works and
what is deliberately not in the path, including
[the ways isolation can still be bypassed](architecture.md#known-ways-isolation-can-still-be-bypassed),
which are documented rather than hidden.

**Something is not working** — [Troubleshooting](troubleshooting.md). "It returns nothing"
and "the policy is not applying" are the first two entries, and between them they cover most
of it.

## Every guide

| Guide | What it answers |
|---|---|
| [Getting started](getting-started.md) | Adding the dependency and getting isolation in ten minutes |
| [Recipes](recipes.md) | Complete solutions: onboarding, suspension, nightly jobs, admin endpoints, Kafka, migrations |
| [Row-level security](row-level-security.md) | How isolation is actually enforced, the whole setup end to end, and the three mistakes that quietly break it |
| [Isolation strategies](isolation-strategies.md) | Row-level security, schema-per-tenant, database-per-tenant, and the discriminator column |
| [Tenant resolution](tenant-resolution.md) | Headers, subdomains, paths, JWT claims, precedence, and writing your own |
| [Securing resolution](securing-resolution.md) | Why a header alone is not enough, and how to close that |
| [Context propagation](context-propagation.md) | The overview: every boundary the tenant has to cross |
| [Async, threads and scheduling](async-and-threads.md) | @Async, virtual threads, CompletableFuture, your own executors, @Scheduled, parallel streams |
| [Outbound HTTP](http-clients.md) | RestClient, RestTemplate, WebClient and Feign — and the builder mistake that silently drops the header |
| [Kafka](kafka.md) | Produce, consume, batch listeners, and the retained tenant that causes cross-tenant writes |
| [The tenant registry](tenant-registry.md) | Who your tenants are, and running work for each of them |
| [Migrations](migrations.md) | Running Flyway across tenants, and the Boot setting you must turn off first |
| [Caching](caching.md) | The one hole row-level security cannot cover, and how it is closed |
| [The isolation checker](isolation-checker.md) | The start-up scan that tells you which tables are not actually protected |
| [Testing](testing.md) | Fixtures, assertions, and how to tell a real isolation test from a vacuous one |
| [Context storage](context-storage.md) | ThreadLocal today, ScopedValue later |
| [Configuration reference](configuration.md) | Every property, and four complete configurations |
| [Architecture](architecture.md) | How the pieces fit together, and why the obvious alternatives are wrong |
| [Adopting in an existing app](adopting-in-an-existing-app.md) | Getting there from a running system, without a flag day |
| [Troubleshooting](troubleshooting.md) | It returns nothing · the policy is not applying · the tenant is null |

## Three isolation strategies

Chosen by one property, with no application code change:

| | Tenants share | Isolated by | Use when |
|---|---|---|---|
| `ROW_LEVEL_SECURITY` | one schema | a Postgres policy | The default. Most SaaS. |
| `SCHEMA_PER_TENANT` | one database | `search_path` | Per-tenant schema shape, or noisy-neighbour queries |
| `DATABASE_PER_TENANT` | nothing | a separate database and pool | Compliance, or a tenant large enough to want its own |

Switching is not behaviour-preserving on the no-tenant path — row-level security returns an
empty result set, schema-per-tenant raises an unresolved relation, database-per-tenant
throws. All three are safe; only the last is impossible to ignore. See
[isolation strategies](isolation-strategies.md).

## The one-paragraph version

Postgres already has row-level security. TenantLayer does not reinvent it — the database
does the enforcing, which is exactly why it is trustworthy. What TenantLayer does is the
wiring around it: getting the tenant onto the connection at the right moment, guaranteeing
it is cleared when the connection returns to the pool, carrying it across every thread and
network boundary in between, failing closed when it is absent, and giving you a test that
proves all of that rather than a README that claims it.

You can also remove it and keep your isolation: the policies are plain SQL you own.
