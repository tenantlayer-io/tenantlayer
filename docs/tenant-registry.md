# The tenant registry

## What it is

One table listing the tenants that exist, their status, and where they live.

```sql
create table tenantlayer_tenants (
    tenant_id      varchar(64) primary key,
    status         varchar(16)  not null default 'ACTIVE',
    region         varchar(64),
    tenant_group   varchar(64),
    datasource_ref varchar(128),
    metadata       jsonb        not null default '{}'::jsonb
);
```

Create it through your own migration tool. TenantLayer does not issue DDL against your
database; `TenantRegistrySchema.DDL` is the statement, for you to commit and review.

The role your application connects as must be able to read it:

```sql
grant select on tenantlayer_tenants to <your application role>;
```

That role is normally neither superuser nor the table's owner — this project tells you not
to make it either, because both bypass row-level security — so the grant does not come for
free. `TenantFilter` reads this table to check the tenant's status (once per tenant every
thirty seconds by default, see below), and without the grant every request fails with
`permission denied for table tenantlayer_tenants` rather than being served. Read is enough;
nothing on the request path writes here.

## It has no row-level security, deliberately

The registry is shared infrastructure that gets consulted *during* tenant resolution,
before any tenant is known. A policy on this table would hide it from the very code whose
job is to read it, and the application would not start. Every other table in your schema
should have one; this one must not.

## Columns, and which are read

| Column | Read by | Meaning |
|---|---|---|
| `tenant_id` | everything | The identifier resolution produces |
| `status` | `TenantFilter`, `forEachTenant`, provisioning | `ACTIVE`, `SUSPENDED` or `PROVISIONING`. Anything other than `ACTIVE` is refused at resolution with a 403 and skipped by iteration. |
| `datasource_ref` | `DATABASE_PER_TENANT` | Which database this tenant lives in — several tenants may share one |
| `region`, `tenant_group` | nothing yet | Reserved |
| `metadata` | your code | Anything you want to hang off a tenant |

`region` and `tenant_group` are present although nothing reads them. A registry is the
hardest table in the system to change once it holds production rows — adding a column later
means a migration on every deployment plus a backfill nobody has the data for. Both are
nullable and cost a schema no one has to alter twice.

`datasource_ref` was the same argument until 0.3.0, and is now the routing key for
[database-per-tenant](isolation-strategies.md): a tenant with a reference uses the database
configured under that name, and a tenant without one uses its own id.

## Reading and writing it

```java
@Autowired TenantRegistry registry;

// Everything about one tenant
Optional<TenantRegistration> acme = registry.find("acme");

// Just the ids of the active ones — what forEachTenant iterates
List<String> active = registry.activeTenantIds();
```

Registering a new tenant is an insert like any other:

```java
registry.save(TenantRegistration.of("acme"));

// Or with a shard and some metadata of your own
registry.save(new TenantRegistration(
        "globex",
        TenantStatus.ACTIVE,
        "eu-west-1",
        "enterprise",
        "shard-a",                       // datasource_ref
        Map.of("plan", "enterprise")));
```

### Suspending a tenant

Suspending a tenant is a status change, and both readers agree on what it means:
`TenantFilter` refuses the tenant with a 403 before any connection is bound, and
`forEachTenant` leaves it out.

It takes effect on the next `forEachTenant` run, and on requests **within the status cache
TTL** — thirty seconds by default (`tenantlayer.registry.status-cache-ttl`). The filter
checks status on every scoped request, so it remembers the answer rather than querying the
table each time; a tenant suspended a moment ago may be served for up to one TTL before the
403 lands. Set the TTL to `0s` if you would rather pay a lookup per request for an instant
cut-off. Only the filter's lookup is cached: `registry.find()` always reads the table, which
is what lets provisioning stay idempotent and iteration stay exact.

The check is on whenever a `TenantRegistry` bean exists. Setting
`tenantlayer.registry.enforce-status=false` turns it off while keeping the registry for
everything else.

```java
registry.find("acme").ifPresent(t -> registry.save(
        new TenantRegistration(t.tenantId(), TenantStatus.SUSPENDED,
                t.region(), t.group(), t.datasourceRef(), t.metadata())));
```

> Suspending does **not** evict what is already cached. See
> [caching](caching.md#evicting-one-tenant) — otherwise a suspended tenant's data stays
> readable until entries expire, which makes "suspended" mean less than it sounds.

## Keeping tenants somewhere else

`TenantRegistry` is an interface. If your tenants live in another service, or a
configuration file, or a table with a different shape, publish a bean and the
autoconfigured JDBC one backs off:

```java
@Bean
TenantRegistry tenantRegistry(CustomerApi customers) {
    return new TenantRegistry() {
        @Override
        public List<String> activeTenantIds() {
            return customers.activeAccountIds();
        }

        @Override
        public Optional<TenantRegistration> find(String tenantId) {
            return customers.lookup(tenantId).map(c -> TenantRegistration.of(c.id()));
        }
        // save / delete / findAll as your source allows
    };
}
```

## Running work for every tenant

This is the case a request-scoped tenancy layer cannot serve. Done wrong it is not an
error: every query runs with no tenant, returns nothing, and the job reports success having
done nothing at all.

```java
@Scheduled(cron = "0 0 3 * * *")
void rebuildReports() {
    tenantTasks.forEachTenant(tenant -> reportService.rebuild());
}
```

Each iteration runs with that tenant bound, so `reportService` needs no tenant parameter
and its queries scope themselves. Suspended tenants are skipped, exactly as their requests
are refused — a tenant is never off for its users and on for the nightly job.

**One tenant's failure does not cancel the rest.** A nightly job that aborts on the first
bad tenant leaves everyone after it in the list unprocessed, and which ones those are
depends on alphabetical order. Every tenant is attempted; failures are collected and thrown
at the end as a `TenantIterationException` that names them, in registry order, so the alert
says *which* tenants failed rather than that something did.

```java
Map<String, Long> counts = tenantTasks.mapEachTenant(tenant -> orders.count());
// {acme=1, globex=2}
```

For a fixed-tenant job:

```java
tenantTasks.runAs("acme", () -> reportService.rebuild());
```

The scheduler thread is left exactly as it was found — schedulers pool their threads, and a
job that leaves a tenant behind hands it to the next job on that thread.

### Handling the failures

```java
try {
    tenantTasks.forEachTenant(tenant -> reportService.rebuild());
} catch (TenantIterationException e) {
    // Named, in registry order — so the alert says which tenants failed, not that some did.
    e.failures().forEach((tenant, cause) ->
            log.error("nightly rebuild failed for {}", tenant, cause));
}
```

The tenants that succeeded stay succeeded. The exception is thrown after every tenant has
been attempted, not on the first failure.
