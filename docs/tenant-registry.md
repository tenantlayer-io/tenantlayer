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

## It has no row-level security, deliberately

The registry is shared infrastructure that gets consulted *during* tenant resolution,
before any tenant is known. A policy on this table would hide it from the very code whose
job is to read it, and the application would not start. Every other table in your schema
should have one; this one must not.

## Columns nothing reads yet

`region` and `tenant_group` are unused in v0.1 and present anyway. A registry is the
hardest table in the system to change once it holds production rows — adding a column later
means a migration on every deployment plus a backfill nobody has the data for. Both are
nullable and cost a schema no one has to alter twice. `datasource_ref` is the same argument
for schema- and database-per-tenant routing.

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
and its queries scope themselves. Suspended tenants are skipped.

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
