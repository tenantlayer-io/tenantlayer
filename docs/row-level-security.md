# Row-level security

## Why the database does the enforcing

An ORM filter is a filter on what the ORM asks for. A native query, a `JdbcTemplate` call,
a bulk `update ... where`, a reporting tool connected to the same credentials — none of
those go through it. A row-level security policy is applied by Postgres to every statement
on that connection regardless of who wrote it or how.

That is the whole reason isolation lives in the database here. TenantLayer's job is the
wiring, which is where hand-rolled implementations go wrong.

## What TenantLayer does on each connection

Every connection handed out of the pool has the tenant published onto it:

```sql
select set_config('tenantlayer.tenant', ?, false)
```

Two decisions in that line are load-bearing.

**It is set on every checkout, unconditionally.** The obvious design — set it when there is
a tenant, reset it on return — has a hole: if the reset is missed for any reason, the value
rides back into the pool and the next borrower inherits it. Setting it on checkout instead
means a connection can never be *used* carrying a stale tenant, because the value is
overwritten before the borrower can issue a statement. It also costs one round trip rather
than two.

**When there is no tenant it is set to the empty string, not left alone.** The policy
compares against `nullif(current_setting(...), '')`, so "no tenant" evaluates to NULL and
matches no rows. Absence of a tenant returns nothing, never everything.

Session scope (`is_local = false`) rather than `SET LOCAL`, because `SET LOCAL` only
survives inside an explicit transaction and plenty of reads run in autocommit.

## The three mistakes

### 1. Forgetting `FORCE ROW LEVEL SECURITY`

Without it the table owner bypasses the policy — and applications very often connect as
the owner. The policy exists, reads correctly, and never applies.

```sql
alter table orders enable row level security;
alter table orders force row level security;   -- this line
```

### 2. Comparing against an unguarded `current_setting`

After a reset, `current_setting('tenantlayer.tenant', true)` returns `''`, not NULL.
Comparing an empty string to a typed column errors in the general case, and an unguarded
comparison can make "no tenant" behave unpredictably.

```sql
using (tenant_id = nullif(current_setting('tenantlayer.tenant', true), ''))
```

### 3. Not indexing the tenant column

The policy predicate is applied to every row read. On a table without an index on the
tenant column, enabling isolation turns every lookup into a sequential scan, and the first
person to find out is a customer.

```sql
create index idx_orders_tenant_id on orders (tenant_id);
```

`RlsPolicyGenerator` emits all three. That is most of why it exists.

## Connection poolers: read this if you use PgBouncer

The tenant is a **session-level** setting, written with
`set_config('tenantlayer.tenant', ?, false)` on every connection checkout. That is safe with
an application-side pool such as HikariCP, where a connection belongs to you until you
return it.

It is **not safe with PgBouncer in transaction or statement pooling mode.**

In those modes a server connection is assigned to you only for the duration of one
transaction. Session state — `SET`, prepared statements, temporary tables — is explicitly
unsupported, because the connection you set the tenant on can be handed to a different
client for the next transaction. The setting can outlive your use of it and be visible to
someone else's query. That is a cross-tenant read, and nothing downstream will catch it.

| Pooler | Mode | Safe |
|---|---|---|
| HikariCP or any in-process pool | — | **yes** |
| PgBouncer | session | **yes** |
| PgBouncer | transaction, session-scoped strategy | **no** |
| PgBouncer | transaction-scoped strategy | **yes** |
| PgBouncer | statement | **no** |
| Supabase Supavisor, RDS Proxy, pgcat | session-equivalent | yes |
| Supabase Supavisor, RDS Proxy, pgcat | transaction, session-scoped strategy | **no** |
| Supabase Supavisor, RDS Proxy, pgcat | transaction-scoped strategy | **yes** |

If you are on transaction pooling today, configure the opt-in transaction-scoped strategy:

```properties
tenantlayer.strategy=ROW_LEVEL_SECURITY_TRANSACTION_SCOPED
```

This strategy waits for JDBC transaction demarcation (`setAutoCommit(false)`), then binds the
tenant with:

```sql
select set_config('tenantlayer.tenant', ?, true)
```

The `true` makes the setting local to that transaction. PostgreSQL removes it at both commit
and rollback, before a PgBouncer transaction-pool connection can be assigned to the next
client. It works with Spring's JDBC transaction managers and with code that uses the same JDBC
transaction lifecycle directly.

The strategy is deliberately fail-closed outside a transaction. Preparing or executing a
statement while auto-commit is still enabled throws instead of running with an empty or stale
tenant. A transaction must therefore surround every database operation on this strategy;
health checks and other genuinely unscoped paths should use a separate, explicitly configured
DataSource. The transaction-scoped wrapper also refuses `Connection.unwrap(...)` so callers
cannot bypass the transaction guard.

The default remains `ROW_LEVEL_SECURITY`, because it safely supports autocommit reads on an
ordinary application-side pool. Do not select the transaction-scoped strategy unless the
application guarantees transaction demarcation for every tenant-scoped database operation.

> **Why this is not simply the default.** `SET LOCAL` requires a transaction to be local to,
> and plenty of reads run in autocommit — a `@Transactional(readOnly = true)` that was
> optimised away, a repository call outside a transaction, a health check. Making isolation
> depend on every read path being transactional is a rule someone eventually breaks, and the
> failure is silent. Session scope on checkout has no such precondition. Transaction-scoped
> binding will be an opt-in strategy for the people who need it, not a replacement.

## Testing against a superuser makes every test meaningless

A Testcontainers Postgres hands you a superuser. Superusers bypass RLS outright, so a suite
written against that connection has the policy in place, never applied, and every isolation
assertion passing for the wrong reason — including after someone deletes the policy.

`TenantPostgres` (see [Testing](testing.md)) gives you a least-privileged connection for
the code under test and a separate privileged one for seeding.

The repository's Testcontainers fixture does not include a PgBouncer service. The strategy's
transaction and RLS tests therefore prove the binding and isolation contract against real
Postgres, but a real PgBouncer transaction-mode multiplexing run must still be supplied by CI
or an environment that provides that proxy.
