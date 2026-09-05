# Design: the isolation strategy seam

**Status:** proposed · **Issue:** #18 · **Blocks:** #3 (schema-per-tenant), #17 (database-per-tenant)

## Why this comes first

`TenantAwareDataSource` today gets a connection from *the* DataSource, then prepares it:

```java
Connection getConnection() { return publishTenant(super.getConnection()); }
```

Row-level security and schema-per-tenant both fit that shape — acquire, then run one
statement. **Database-per-tenant does not.** It has to choose a different pool *before*
acquiring anything.

So the seam belongs at connection **acquisition**, not connection **preparation**. Build
#3 and #17 first and the switch gets retrofitted around whatever shape they happened to
take, which means a breaking change in 0.3 for anyone who implemented against 0.2.

## The interface

```java
public interface TenantConnectionStrategy {

    /** A connection prepared for whatever tenant is currently bound, or for none. */
    Connection getConnection() throws SQLException;

    Connection getConnection(String username, String password) throws SQLException;

    /** For diagnostics and for the isolation checker. */
    String name();

    /**
     * Whether this strategy expects row-level security policies to exist.
     * Default true, so an implementation written before this method existed keeps
     * behaving as it did.
     */
    default boolean expectsRowLevelSecurity() { return true; }
}
```

Every method added later must be `default`. This is implemented by users, so a new abstract
method is a breaking change; a default one is not.

## What each implementation does

| Strategy | Acquires from | Prepares by | No tenant bound |
|---|---|---|---|
| `ROW_LEVEL_SECURITY` | the single pool | `set_config('tenantlayer.tenant', ?, false)` | setting is `''` → policy matches nothing → **no rows** |
| `SCHEMA_PER_TENANT` | the single pool | `set search_path` to the tenant's schema | `search_path` set to empty → unqualified tables do not resolve → **error** |
| `DATABASE_PER_TENANT` | a pool chosen for the tenant | nothing — the pool *is* the isolation | no pool to choose → **throws** |

`TenantAwareDataSource` stays, with its current name and behaviour, and becomes a thin
delegate onto the configured strategy. It is public API in 0.1.0 and must not move.

## The thing worth arguing about: fail-closed is not uniform

"Fails closed" is the brand promise, and these three fail closed *differently*:

- RLS returns an empty result set
- Schema-per-tenant raises `relation "orders" does not exist`
- Database-per-tenant throws before a connection exists

All three are safe — none of them leaks. But an application that silently copes with empty
results will crash under the other two, so **switching strategy is not behaviour-preserving
for the no-tenant path.** That has to be documented as a property of the switch rather than
discovered during a migration.

Deliberate position: do not paper over this by making the other two return empty results.
An error is a better failure than silence, and the RLS one is empty only because that is
what a policy does.

## Configuration

```properties
tenantlayer.strategy=ROW_LEVEL_SECURITY        # default, and what 0.1.0 does
tenantlayer.schema.prefix=tenant_              # SCHEMA_PER_TENANT
```

Default stays `ROW_LEVEL_SECURITY`, so upgrading changes nothing.

## Consequences worth accepting now

**The registry becomes required for `DATABASE_PER_TENANT`.** It is the only thing that
knows which datasource a tenant lives on — that is what `TenantRegistration.datasourceRef`
was reserved for in 0.1.0. The other two strategies keep working without it.

**`RlsPolicyGenerator` becomes strategy-specific.** Generating RLS policies under
`DATABASE_PER_TENANT` is meaningless. `expectsRowLevelSecurity()` is what lets the
generator and the future isolation checker (#21) know to stay quiet.

**Migrations diverge.** One schema, N schemas and N databases are three different
problems. #9 (Flyway runner) should be built against this interface rather than assuming a
single target — otherwise it gets rewritten at 0.3.

**Pool lifecycle at scale is explicitly out of scope.** `DATABASE_PER_TENANT` creates pools
lazily with a fixed size and never evicts. Eviction, warm-up and thousands of pools are a
paid concern; correctness is not.

## What I am not proposing

- No change to `TenantContext`, `TenantResolver` or anything above the DataSource. The
  strategy sits strictly below the point where the tenant is already known.
- No runtime strategy switching. It is start-up configuration; switching per request would
  be a way to read another tenant's data by changing one property.
