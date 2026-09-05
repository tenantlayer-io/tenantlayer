# Troubleshooting

Almost every problem here has the same shape: **something returns nothing, and nothing
throws.** Work down this page in order — the first two causes account for most reports.

## My queries return no rows

### Is a tenant actually bound?

```java
System.out.println(TenantContext.current());   // Optional.empty means no tenant
```

If it is empty, the tenant never arrived. That is a *resolution* or *propagation* problem,
not a policy problem. Jump to [the tenant is null](#the-tenant-is-null-somewhere-it-should-not-be).

### Is the connection carrying it?

Ask the database directly, on the same connection your application uses:

```sql
select current_setting('tenantlayer.tenant', true);
```

Empty string means no tenant was published. A value that is not the tenant you expect means
something rebound it.

### Do the rows have the tenant you think?

```sql
-- as a privileged role, which bypasses the policy
select tenant_id, count(*) from orders group by tenant_id;
```

A common cause: rows were inserted before the tenant column had a default, so they carry
`''` or NULL and are now invisible to everyone.

## My policy is not applying — everyone sees everything

**In order of how often this is the cause:**

### 1. You are connecting as a superuser

Superusers bypass row-level security entirely. Check:

```sql
select current_user, usesuper from pg_user where usename = current_user;
```

If `usesuper` is true, the policy is in place and will never be applied. Create a
least-privileged role and connect as that:

```sql
create role orders_app login password '...';
grant usage on schema public to orders_app;
grant select, insert, update, delete on orders to orders_app;
```

This is also why a test suite can pass while isolation is broken. If your tests connect as
the container's default superuser, they prove nothing.

### 2. You are the table owner and `FORCE` is not set

The owner bypasses policies unless forced:

```sql
select relname, relrowsecurity, relforcerowsecurity
from pg_class where relname = 'orders';
```

Both must be true:

```sql
alter table orders enable row level security;
alter table orders force row level security;
```

### 3. The policy does not exist

```sql
select polname, pg_get_expr(polqual, polrelid) from pg_policy
join pg_class on pg_class.oid = polrelid where relname = 'orders';
```

Generate it rather than writing it by hand — see [Row-level security](/docs/row-level-security).

## The tenant is null somewhere it should not be

| Where | Cause | Fix |
|---|---|---|
| Inside `@Async` | Virtual threads are on and you are on an older version | Upgrade; both executor types are decorated |
| Inside `CompletableFuture.supplyAsync(...)` | No executor given, so it ran on the common ForkJoinPool | `TenantExecutors.supplier(...)` or pass a wrapped executor |
| Inside a `@Scheduled` job | No request, so nothing ever bound one | `tenantTasks.forEachTenant(...)` or `runAs(...)` |
| Inside a Kafka listener | Record carries no tenant header | The producer must be going through the interceptor |
| Inside a `new Thread(...)` | Nothing decorates a raw thread | `TenantExecutors.runnable(...)` |
| In a `@PostConstruct` | Startup, before any request | Do not read the tenant at startup |

## Every request gets 400 "No tenant could be resolved"

Strict mode is doing its job. Either the request genuinely carries no tenant, or your
resolver cannot see it.

- **Header** — is it the header you configured? `tenantlayer.header` defaults to `X-Tenant-ID`.
- **Subdomain** — is `tenantlayer.base-domain` set? Without it the first host label is used,
  and `localhost` has none. Note the subdomain resolver deliberately refuses `www` and bare
  base domains rather than inventing a tenant.
- **Path** — does the path start with `tenantlayer.path-prefix` (default `/t`)?
- **JWT** — see the next section; this one has a specific trap.
- **Health checks failing** — add them to `tenantlayer.unscoped-paths`.

## My JWT claim resolver never resolves anything

The resolver reads the `SecurityContext`, which Spring Security populates in **its** filter
chain. If `TenantFilter` runs before that chain, it sees an empty context on every request
and falls through to the next resolver — usually the header, which is exactly what you added
the claim resolver to outrank.

Nothing fails. Isolation quietly reverts to trusting the client.

TenantLayer moves the filter automatically when `tenantlayer.resolvers` includes `JWT` or
membership verification is enabled. If you have overridden `tenantlayer.filter-order`, that
is the first thing to check.

Then confirm the claim name matches your provider — `tenantlayer.jwt-claim` defaults to
`tenant_id`, and providers disagree.

## Everyone gets 403

Membership verification is refusing the resolved tenant.

A token grants membership through either a claim listing permitted tenants
(`tenantlayer.membership.claim`, default `tenants`) or a `TENANT_<id>` authority. **A token
with no tenant claim at all grants nothing** — absence of a restriction is not permission,
deliberately.

Decode a token and check the claim is present, is spelled as configured, and contains the
tenant being requested.

## One tenant can see another's data

Stop and treat this as an incident.

1. Are you connecting as a superuser or unforced owner? (See above — this is usually it.)
2. Is the data coming from a **cache** that you named under `tenantlayer.cache.shared`, or
   is `tenantlayer.cache.enabled=false`? A cache hit never reaches the database, so no
   policy can help. This is the most likely cause if the SQL looks right.
3. Is the query running through a connection that was unwrapped to a raw `PgConnection`?
4. Does the table have a policy at all? A table added recently may have been missed.

If none of those explain it, [report it privately](https://github.com/tenantlayer-io/tenantlayer/security/advisories/new)
— not in a public issue.

## Performance got worse after enabling RLS

The policy predicate is evaluated against every row read. Without an index on the tenant
column, every lookup becomes a sequential scan.

```sql
create index if not exists idx_orders_tenant_id on orders (tenant_id);
```

The generator emits this; hand-written policies routinely forget it. Confirm with
`explain (analyze, buffers)` that you are getting an index scan.

## My tests pass but I do not believe them

Correct instinct. Run them with the library switched off:

```
mvn test -Dspring.autoconfigure.exclude=io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration
```

If your isolation tests still pass, they are not testing isolation. See
[Testing](/docs/testing) for why `assertTenantCannotSee` is deliberately two-sided.

## Still stuck

Ask in [Discussions](https://github.com/tenantlayer-io/community/discussions) with your
`tenantlayer.*` configuration, the role your application connects as, and the output of
`select current_user, usesuper from pg_user where usename = current_user`. Those three
answer most questions immediately.
