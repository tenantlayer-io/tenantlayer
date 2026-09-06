# Migrations

Once there is more than one schema, `flyway migrate` stops being a single command. That is
the point at which migrations become an operational problem rather than a build step.

## Turn off Boot's automatic migration first

**This is the first thing to do, and skipping it is confusing rather than obviously wrong.**

Simply having `flyway-core` on the classpath makes Spring Boot auto-configure a
single-schema migration that runs at every application start, against whatever the
connection's default schema is. Under schema-per-tenant that is not where your tables live,
and under a shared schema it will race with the runner below.

```properties
spring.flyway.enabled=false
```

Then run migrations explicitly.

## Running them

```java
@Autowired TenantMigrationRunner migrations;

MigrationOutcome outcome = migrations.migrateAll();   // every active tenant
migrations.migrate("acme");                           // just one
```

```properties
tenantlayer.migration.locations=classpath:db/tenant-migration
```

## It asks the strategy rather than assuming

Under a **shared schema** — which is what row-level security uses — there is one set of
tables and one migration history. `migrateAll()` runs **once**, not once per tenant.
Looping would replay the same migrations against the same tables, and Flyway would refuse.

Under **schema-per-tenant** it runs once per active tenant, against that tenant's schema,
creating the schema if it does not exist so a newly provisioned tenant needs no
out-of-band setup.

That decision comes from `TenantConnectionStrategy.schemaFor(tenantId)` rather than from
configuration, so database-per-tenant slots in later without this being rewritten.

## Migrations bypass the tenant-aware DataSource

Deliberately. That wrapper sets the tenant, or the `search_path`, on every checkout from
whatever is in the context — and during a migration the context is empty or belongs to
whichever tenant happened to be bound. Flyway is given the schema explicitly instead, on
the underlying pool.

You therefore do not need a tenant bound to run migrations, and binding one changes
nothing.

## One tenant failing does not stop the others

A run that aborts on the first bad tenant leaves everyone after it unmigrated, and which
ones those are depends on alphabetical order. Every tenant is attempted; failures are
collected and thrown at the end as a `TenantMigrationException` naming them, in registry
order.

```java
try {
    migrations.migrateAll();
} catch (TenantMigrationException e) {
    e.outcome().failedTenants();          // ["globex"]
    e.outcome().migrated();               // {"acme": 2}
}
```

**Tenants can therefore be at different schema versions.** That is a real operational
state, not an error — and it is why the exception names them rather than reporting a count.

## Adopting Flyway into a database that already exists

`baselineOnMigrate` marks the current state as version 1 and skips migrations at or below
it. That is usually what you want when adopting Flyway into an existing schema — and it
means **`V1__…sql` will not run**, which surprises people whose V1 creates the tables.

```properties
tenantlayer.migration.baseline-on-migrate=true
```

If you need V1 to run against an already-populated schema, baseline at version 0 instead,
or number your first real migration V2.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.migration.enabled` | `true` | Expose the runner |
| `tenantlayer.migration.locations` | `classpath:db/tenant-migration` | Where tenant migrations live |
| `tenantlayer.migration.baseline-on-migrate` | `false` | Baseline an existing schema at version 1 |

Keep tenant migrations **out of `classpath:db/migration`** — that is Boot's default
location, and anything there is picked up by the automatic single-schema migration you
turned off above, should it ever be turned back on.
