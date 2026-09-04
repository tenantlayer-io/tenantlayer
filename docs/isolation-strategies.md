# Isolation strategies

## Discriminator column

Every tenant's rows share a table, separated by a column. The strategy most SaaS starts
with, and the one to start with unless you have a reason not to.

```java
@Entity
class Note {
    @Id @GeneratedValue Long id;

    @TenantId
    private String tenantId;

    private String body;
}
```

Hibernate adds the predicate to reads and stamps the column on writes. The application
never mentions the tenant. TenantLayer supplies the `CurrentTenantIdentifierResolver` that
makes it work, wired automatically (`tenantlayer.discriminator.enabled`, on by default).

When no tenant is bound, the resolver returns the empty string. Reads then match nothing
and writes stamp a value no tenant uses, so work attempted without a tenant does nothing
rather than touching everyone's rows. Returning `"default"` or the first known tenant would
be the opposite of that.

## Row-level security

The same shared table, with Postgres applying the predicate instead of Hibernate. See
[Row-level security](row-level-security.md).

## Use both

They are not alternatives, and the difference is what happens when someone steps outside
the ORM.

| | Discriminator | RLS |
|---|---|---|
| JPA queries | filtered | filtered |
| Native SQL, `JdbcTemplate` | **not filtered** | filtered |
| Bulk `update`/`delete` | partly | filtered |
| A psql session on the same credentials | not filtered | filtered |
| Requires Postgres | no | yes |

The discriminator gives you a tenant column that is populated correctly without any code
remembering to do it. RLS gives you the guarantee that holds when code does something the
ORM never sees. Enabling both costs one annotation and one policy.

Note what neither covers: a connection that has been unwrapped to a raw `PgConnection`, and
anything running as superuser. The first is why enforcement at the JDBC layer is a Pro
concern (Tenant Guard); the second is why your application must not connect as one.

## Schema-per-tenant and database-per-tenant

v0.2 and v0.3 respectively. The registry already carries `datasource_ref` so that adopting
them later is a routing change rather than a migration.
