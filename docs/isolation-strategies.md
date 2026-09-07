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

Both are selected by configuration and need no application change:

```properties
tenantlayer.strategy=SCHEMA_PER_TENANT     # one schema per tenant, one pool
tenantlayer.strategy=DATABASE_PER_TENANT   # one database per tenant, a pool each
```

### Database-per-tenant

The pool *is* the isolation. A connection handed to acme is physically attached to acme's
database, so there is no policy to get wrong and no session variable to leak across a
pooled checkout. Declare the databases:

```properties
tenantlayer.strategy=DATABASE_PER_TENANT

tenantlayer.databases.acme.url=jdbc:postgresql://db-1:5432/acme
tenantlayer.databases.acme.username=acme_app
tenantlayer.databases.acme.password=${ACME_DB_PASSWORD}

tenantlayer.databases.shard-a.url=jdbc:postgresql://db-2:5432/shard_a
tenantlayer.databases.shard-a.username=app
tenantlayer.databases.shard-a.password=${SHARD_A_PASSWORD}

tenantlayer.databases-max-pools=50
```

**You must declare the Hibernate dialect.** Hibernate works out which dialect to use at
start-up by asking a connection for its metadata — and at start-up no tenant is bound, so
under this strategy there is no database to ask. Without this the application does not
start, and the error names the dialect rather than the tenancy:

```properties
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```

> `Unable to determine Dialect without JDBC metadata` on start-up means exactly this.

Keys are **database references**, not tenant ids. A tenant is mapped to one through
`datasource_ref` in the [registry](tenant-registry.md), which is how a hundred small
tenants share a shard while a large one gets a database to itself. A tenant with no
`datasource_ref` uses its own id as the reference — the plain one-database-per-tenant case,
with nothing extra to configure.

Pools open on first use, never speculatively. Once `databases-max-pools` is reached the
next new database throws rather than evicting: an idle pool cannot be closed safely without
knowing whether a connection from it is still in flight, and silently recycling pools turns
a capacity problem into intermittent failures under load.

**It fails closed, loudly.** No tenant bound, or a tenant with no configured database, and
you get an exception before a connection exists — never a fall back to the application's
main datasource. That datasource is somebody's database, and serving it to an unrecognised
tenant is the exact cross-tenant read this strategy exists to prevent.

This is louder than the other two on the no-tenant path. Row-level security returns an empty
result set, schema-per-tenant raises an unresolved relation, and this throws. All three are
safe; only this one is impossible to ignore — so an application that quietly copes with
empty results will start failing when you switch to it. That is a property of the switch,
not a bug in it.

### Supplying databases from somewhere else

Connection details often live in a secrets manager that issues short-lived credentials
rather than in a properties file. Publish a `TenantDataSourceProvider` bean and it is used
instead of the configuration above:

```java
@Bean
TenantDataSourceProvider tenantDataSourceProvider(VaultClient vault) {
    return tenantId -> vault.lease(tenantId).map(this::poolFor);
}
```

Return empty for a tenant you do not recognise. Do not substitute a default — that is the
one thing the strategy cannot check for you.

### Migrations

Each tenant has its own database, so schema changes must reach every one of them.
`TenantMigrationRunner` handles this: it asks the strategy whether migrations are per-tenant
and, under this strategy, runs Flyway once per tenant against that tenant's own datasource.
See [migrations](migrations.md).

Note that `schemaFor` is empty here — tenants share a schema *name* and merely live in
different databases — which is why the runner asks `migratesPerTenant()` instead of
inferring it from the schema.
