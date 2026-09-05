# Adopting it in an existing application

Every other guide assumes a new project. This one assumes the harder and more common case:
a multi-tenant application already in production, with real data, a `tenant_id` column
everyone remembers to filter on, and no appetite for a risky weekend.

The goal is to end up with isolation enforced by the database **without a flag day**, and
with a point at which you can prove it works before you rely on it.

## Before you start: two things to check

**What role does your application connect as?** If it is a superuser or the table owner,
nothing in this guide will work until that changes, and worse, your verification will pass
while isolation does nothing. Find out first:

```sql
select current_user, usesuper from pg_user where usename = current_user;
```

**Is your `tenant_id` column complete?** Any row where it is NULL or `''` becomes invisible
to everyone the moment a policy is applied.

```sql
select count(*) from orders where tenant_id is null or tenant_id = '';
```

Fix those rows before going any further. This is the step that turns a smooth adoption into
an incident.

## Step 1 — Add the library, resolve nothing

```xml
<dependency>
  <groupId>io.tenantlayer</groupId>
  <artifactId>tenantlayer-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```properties
tenantlayer.strict=false
```

`strict=false` is temporary and deliberate. It means a request with no resolvable tenant is
**not** rejected — your application keeps behaving exactly as it does today while you get
the plumbing in place. Nothing is enforced yet.

Deploy this. Nothing should change.

## Step 2 — Resolve the tenant, and check it against what you already do

Configure whichever resolver matches how you identify tenants today, then compare the
library's answer against your existing mechanism before trusting it:

```java
@Component
class ResolutionShadowCheck {

    private static final Logger log = LoggerFactory.getLogger(ResolutionShadowCheck.class);

    void compare(String ourAnswer) {
        String theirs = TenantContext.current().map(TenantScope::subject).orElse(null);
        if (!Objects.equals(ourAnswer, theirs)) {
            log.warn("tenant mismatch: application={} tenantlayer={}", ourAnswer, theirs);
        }
    }
}
```

Run this in production for as long as it takes to see real traffic — a day is usually
enough, a week if your traffic is uneven. **Zero mismatches is the gate for the next step.**
A mismatch here would become a wrong-tenant read later.

## Step 3 — Create a least-privileged role

Your application almost certainly connects as the owner or a superuser today. Add a role
that is neither, grant it exactly what the application needs, and switch the connection
string.

```sql
create role orders_app login password '...';
grant usage on schema public to orders_app;
grant select, insert, update, delete on orders, invoices to orders_app;
grant usage, select on all sequences in schema public to orders_app;
```

Deploy and confirm the application still works. Still nothing is enforced — you have only
stopped being exempt from enforcement that does not yet exist.

This is the step teams skip, and skipping it makes every later step a no-op.

## Step 4 — Generate the policies and read them

```java
@Autowired TenantScopedEntityScanner scanner;
@Autowired RlsPolicyGenerator generator;

System.out.println(generator.generate(scanner.scan()));
```

Check the list of tables it found. Exclude anything shared that happens to carry a
`tenant_id` audit column:

```properties
tenantlayer.schema.excludes=countries,feature_flags
```

Commit the SQL as a migration. Do not apply it yet.

## Step 5 — Prove it on a copy first

Restore a production snapshot somewhere safe, apply the migration, and run this against it:

```java
@Test
@WithTenant("a-real-tenant-id")
void isolationHoldsOnRealData() {
    assertThat(orders.findAll()).isNotEmpty();      // this tenant still sees its own
    assertTenantCannotSee("another-real-tenant-id"); // and none of the other's
}
```

Both halves matter. The second one alone would pass against an empty table.

Then run your **existing** test suite against the copy. Anything that breaks is a query that
was implicitly relying on cross-tenant visibility — a report, an admin screen, a nightly
job. Better to find those here.

## Step 6 — Apply the policies

Now apply the migration in production. Your application already sends the tenant on every
connection, so behaviour should not change: the database starts enforcing what your code was
already doing.

The failure mode to watch for is **empty results**, not errors. Watch for endpoints that
suddenly return nothing — that is a code path where the tenant was not being bound and you
had not noticed, because your `WHERE tenant_id` was doing the work.

## Step 7 — Turn on fail-closed

```properties
tenantlayer.strict=true
```

Now a request with no resolvable tenant is rejected with 400 rather than served. Do this
last, once you know every path resolves a tenant.

## Step 8 — Delete your `WHERE tenant_id` clauses

Optional, and worth doing. Once the database enforces it, the manual predicates are
redundant. Removing them is what stops the next person forgetting one.

Remove them gradually and keep the tests.

## Where this gets harder

**Background jobs and schedulers** have no request to resolve from, and are the most common
source of "worked in staging, empty in production". See
[the tenant registry](/docs/tenant-registry) for `forEachTenant`.

**Caching** is the one thing this sequence does not fix. A cache hit never reaches the
database, so no policy can help. Audit every `@Cacheable` on a tenant-scoped result and key
it by tenant yourself until tenant-scoped cache keys ship.

**Cross-tenant admin screens** genuinely need to see everything. Today the honest answer is
a separate connection using a role that is exempt from the policy, kept well away from
request handling. A governed escape hatch is a paid feature and is not in the free core.

## If you have to roll back

Dropping the policies restores the previous behaviour immediately:

```sql
drop policy if exists orders_tenant_isolation on orders;
alter table orders no force row level security;
alter table orders disable row level security;
```

Your application keeps working, because it was already sending the tenant and your original
`WHERE tenant_id` clauses are still there if you have not removed them yet. That is the
reason step 8 comes last.
