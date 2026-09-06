# Architecture

## The path of one request

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/architecture-dark.png">
  <img alt="A request enters, Spring Security optionally authenticates, TenantFilter resolves and verifies and binds the tenant, your own code runs with no knowledge of tenancy, TenantAwareDataSource publishes the tenant onto the connection on every checkout, and Postgres row-level security is the only component that enforces." src="images/architecture-light.png" width="1040">
</picture>

The important property is that **nothing in the middle knows about tenancy**. Your
controller, service and repository are written as if the application had one customer. The
tenant is established before them and enforced after them.

## Components, and what each is responsible for

| Component | Responsibility | Fails how |
|---|---|---|
| `TenantResolver<S>` | Reports which tenant a request *claims* | Returns empty; the chain falls through |
| `TenantResolverChain` | Ordered precedence, first match wins | — |
| `TenantMembershipVerifier` | Decides whether the caller is *entitled* to that tenant | Returns false → 403, before any binding |
| `TenantFilter` | Binds the scope for the request, unwinds it after | No tenant + strict → 400 |
| `TenantContext` | The single place the current tenant is read or written | `require()` throws `NoTenantException` |
| `TenantContextStorage` | Where the value physically lives (ThreadLocal today) | — |
| `TenantTaskDecorator` / `TenantExecutors` | Carry the scope across thread boundaries | Task runs with no tenant → reads nothing |
| `TenantAwareDataSource` | Publishes the tenant onto every pooled connection | Connection is closed rather than handed over |
| `TenantRegistry` | Who the tenants are | Throws `TenantRegistryException` |
| Postgres RLS policy | **Enforces** | Returns no rows |

Only the last row enforces. Everything above it is plumbing whose job is to make sure the
database is asked the right question.

## Why the enforcement lives in the database

An ORM filter constrains what the ORM asks for. A native query, a `JdbcTemplate` call, a
bulk `update ... where`, a reporting tool on the same credentials, a psql session — none of
those pass through it. A row-level security policy is applied by Postgres to every statement
on that connection, regardless of who wrote it or how.

That is the whole reason this design puts the predicate in the database and treats the Java
side as wiring. It also means TenantLayer can be removed and your isolation still holds:
the policies are plain SQL you own.

## Three decisions worth understanding

### The tenant is set on checkout, not reset on return

The obvious design is: set the tenant when there is one, reset it when the connection goes
back to the pool. It has a hole. If the reset is missed — an exception on the return path, a
code path that bypasses close — the value rides back into the pool and the next borrower
inherits it silently.

Setting it on *every* checkout means a connection can never be **used** carrying a stale
tenant, because the value is overwritten before the borrower can issue a statement. It is
also one round trip instead of two.

### No tenant is written as the empty string, not left alone

With nothing bound, the setting is written as `''`. The generated policy compares against
`nullif(current_setting('tenantlayer.tenant', true), '')`, so absence evaluates to NULL and
matches no rows.

Absence of a tenant therefore returns **nothing**, never everything. Fail-closed is a
property of the SQL, not of remembering to check.

### Session scope, not `SET LOCAL`

`SET LOCAL` only survives inside an explicit transaction, and plenty of reads run in
autocommit. Session scope with an unconditional overwrite on checkout gives the same safety
without requiring every read to be transactional.

## Extension points

Each of these is one interface, and defining a bean makes the autoconfiguration back off:

- **`TenantResolver<S>`** — resolve from an API key, an mTLS certificate, a message header
- **`TenantMembershipVerifier`** — back membership with a database table, mTLS, an internal token
- **`TenantContextStorage`** — swap where the tenant is kept
- **`TenantRegistry`** — keep tenants somewhere other than a table

## What is deliberately not in the path

- **No agent, no bytecode weaving, no proxy of your entities.** The only wrapping is a
  `DataSource` delegate.
- **No phone-home, no telemetry.**
- **No runtime dependency on the registry** for request handling — the registry is consulted
  for iteration and, if you enable it, membership. A request that resolves from a token does
  not touch it.
- **No enforcement in Java.** Statement-level enforcement is a separate, paid concern
  precisely because doing it in Java is a weaker guarantee than doing it in the database.

## Known ways isolation can still be bypassed

Documented rather than hidden, because you should design around them:

1. **Connecting as a superuser, or as the table owner without `FORCE ROW LEVEL SECURITY`.**
   Both bypass policies by design. This is the single most common cause of "my policy is not
   working" — and of test suites that pass for the wrong reason.
2. **Unwrapping a pooled connection to a raw `PgConnection`.** Code that does this holds a
   connection outside the wiring from that point on. The session variable is still set, so
   policies still apply — but anything that changes the session is now your responsibility.
3. **PgBouncer in transaction or statement pooling mode.** The tenant is session-level
   state, and in those modes a server connection is yours for only one transaction — so the
   setting can outlive your use of it and be visible to another client. Session pooling is
   fine. See [Row-level security](row-level-security.md#connection-poolers-read-this-if-you-use-pgbouncer).
4. **Caching, if you disable it.** A cache hit never reaches the database, so no policy
   can help. TenantLayer qualifies cache keys by tenant for exactly this reason — but
   setting `tenantlayer.cache.enabled=false`, or naming a tenant-scoped cache under
   `tenantlayer.cache.shared`, reopens it. See [Caching](caching.md).
