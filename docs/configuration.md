# Configuration reference

Every property, with its default.

## Resolution

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.resolvers` | `HEADER` | Ordered list of `HEADER`, `SUBDOMAIN`, `PATH`, `JWT`. Order is precedence. |
| `tenantlayer.header` | `X-Tenant-ID` | Header the header resolver reads, and the header outbound calls set. |
| `tenantlayer.base-domain` | *(unset)* | Domain the subdomain resolver strips, e.g. `app.com`. Unset, the first host label is used. |
| `tenantlayer.path-prefix` | `/t` | Prefix the path resolver matches, e.g. `/t/acme/orders`. |
| `tenantlayer.jwt-claim` | `tenant_id` | Token claim the JWT resolver reads. |
| `tenantlayer.strict` | `true` | Reject requests with no resolvable tenant. Leave it on. |
| `tenantlayer.strategy` | `ROW_LEVEL_SECURITY` | Isolation strategy: `ROW_LEVEL_SECURITY`, `SCHEMA_PER_TENANT` or `DATABASE_PER_TENANT`. Chosen once at start-up, never per request. |
| `tenantlayer.databases.<ref>.url` | *(unset)* | JDBC URL for one tenant database under `DATABASE_PER_TENANT`. `<ref>` is the tenant's `datasource_ref`, or its id when it has none. |
| `tenantlayer.databases.<ref>.username` | *(unset)* | Username for that database. Omit when the URL carries it. |
| `tenantlayer.databases.<ref>.password` | *(unset)* | Password for that database. |
| `tenantlayer.databases.<ref>.max-pool-size` | *(pool default)* | Per-tenant pool ceiling. |
| `tenantlayer.databases-max-pools` | `50` | How many tenant pools may be open at once. Exceeding it throws rather than evicting a live pool. |
| `tenantlayer.unscoped-paths` | `/actuator`, `/error` | Path prefixes served without a tenant. |
| `tenantlayer.filter-order` | *(derived)* | Servlet filter order. Derived: near-first normally, after Spring Security when resolution or membership needs an authenticated principal. |

## Membership verification

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.membership.enabled` | `false` | Verify the principal is entitled to the resolved tenant. Off by default because enabling it without tenant claims in your tokens rejects every request. |
| `tenantlayer.membership.claim` | `tenants` | Token claim listing the tenants the bearer may act as. |

Requires Spring Security on the classpath.

## Registry

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.registry.enabled` | `true` | Expose a `TenantRegistry` backed by the application DataSource. Iteration, provisioning and status enforcement all need it, so the table must exist. |
| `tenantlayer.registry.table` | `tenantlayer_tenants` | Table the registry reads and writes. Validated as a plain SQL identifier. |
| `tenantlayer.registry.enforce-status` | `true` | `TenantFilter` refuses a tenant whose registry status is not `ACTIVE` with a 403, before it is bound. Off keeps the registry for iteration and provisioning but takes the lookup off the request path. |
| `tenantlayer.registry.status-cache-ttl` | `30s` | How long the filter trusts a tenant's status before asking the registry again. Suspension takes effect within this window, not instantly. `0s` looks the status up on every scoped request. Only the filter's lookup is cached; `TenantRegistry.find()` itself is not. |

## Schema scanning and policy generation

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.schema.tenant-column` | `tenant_id` | Column that marks a table tenant-scoped. |
| `tenantlayer.schema.includes` | *(empty)* | Tables or entity names to treat as tenant-scoped regardless of columns. |
| `tenantlayer.schema.excludes` | *(empty)* | Tables or entity names never to treat as tenant-scoped. |

## Strategies and integrations

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.strategy` | `ROW_LEVEL_SECURITY` | `ROW_LEVEL_SECURITY` publishes a session-scoped tenant on checkout; `ROW_LEVEL_SECURITY_TRANSACTION_SCOPED` binds with `SET LOCAL` at the Spring transaction boundary and rejects tenant-scoped access outside a transaction; `SCHEMA_PER_TENANT` selects `search_path`. |
| `tenantlayer.discriminator.enabled` | `true` | Register the Hibernate tenant identifier resolver that makes `@TenantId` work. |
| `tenantlayer.kafka.enabled` | `true` | Register the Kafka producer/consumer interceptors. |

## Optional dependencies

Nothing below is required. Each unlocks the features next to it, and the library starts
without any of them.

| Dependency | Enables |
|---|---|
| `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` | JWT resolver, membership verification |
| `spring-kafka` | Kafka produce/consume propagation |
| `spring-webflux` | `WebClient` outbound propagation |
| `feign-core` | Feign outbound propagation |
| `org.testcontainers:postgresql` | `TenantPostgres` test fixture |

## Complete configurations

Four setups that work as written. Start from whichever is closest.

### A SaaS with signed-in users

The common case: an identity provider issues tokens carrying the tenant, and one database
holds every tenant's rows behind a policy.

```properties
tenantlayer.resolvers=JWT
tenantlayer.jwt-claim=tenant_id
tenantlayer.strategy=ROW_LEVEL_SECURITY
tenantlayer.strict=true
tenantlayer.unscoped-paths=/actuator,/error

spring.security.oauth2.resourceserver.jwt.issuer-uri=https://your-idp.example.com/

# Connect as a role that is neither superuser nor table owner, or the policy never applies.
spring.datasource.url=jdbc:postgresql://localhost:5432/app
spring.datasource.username=app_user
spring.datasource.password=${DB_PASSWORD}
```

### Tenants on their own subdomains

```properties
tenantlayer.resolvers=SUBDOMAIN,JWT
tenantlayer.base-domain=app.com
tenantlayer.strategy=ROW_LEVEL_SECURITY

# A caller cannot reach another tenant by changing the host: the tenant they
# claim is checked against the tenants their token says they belong to.
tenantlayer.membership.enabled=true
tenantlayer.membership.claim=tenants
```

### Internal services behind a trust boundary

Header resolution is fine when the network is the boundary. The same header is sent on
outbound calls, so a chain of services propagates the tenant with no code.

```properties
tenantlayer.resolvers=HEADER
tenantlayer.header=X-Tenant-ID
tenantlayer.strategy=ROW_LEVEL_SECURITY
tenantlayer.strict=true
```

> If any of these services becomes reachable from outside, turn on membership verification.
> A header is a claim, not a proof.

### A database per tenant

```properties
tenantlayer.strategy=DATABASE_PER_TENANT

# Required: Hibernate resolves its dialect at start-up, when no tenant is bound and
# therefore no database can be chosen for it.
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect

tenantlayer.databases.acme.url=jdbc:postgresql://db-1:5432/acme
tenantlayer.databases.acme.username=app
tenantlayer.databases.acme.password=${ACME_DB_PASSWORD}

tenantlayer.databases.globex.url=jdbc:postgresql://db-2:5432/globex
tenantlayer.databases.globex.username=app
tenantlayer.databases.globex.password=${GLOBEX_DB_PASSWORD}

tenantlayer.databases-max-pools=50
```

Keys are database *references*. A tenant is mapped to one through `datasource_ref` in the
registry, so several tenants can share a shard; a tenant with no reference uses its own id.

## Configuration for tests

Tests need the opposite of production: predictable, no external services, and loud when
isolation breaks.

```properties
# Resolve from a header so tests can set the tenant without minting tokens.
tenantlayer.resolvers=HEADER
tenantlayer.strict=true

# Boot auto-configures Flyway from the classpath alone. Leave it on in a test that is
# not about migrations and it will run them against your test schema.
spring.flyway.enabled=false
```

See [testing](testing.md) for the test kit, and
[adopting it in an existing application](adopting-in-an-existing-app.md) for turning this
on gradually rather than all at once.

## Things worth setting deliberately

| Property | Why it matters |
|---|---|
| `tenantlayer.strict` | Off, an unresolved tenant returns an empty result set — which looks like "no data" rather than a bug. Leave it on. |
| `tenantlayer.unscoped-paths` | The list of routes where isolation does not apply. Keep it short and specific. |
| `tenantlayer.resolvers` | Order is precedence. The most trusted source goes first. |
| `tenantlayer.membership.enabled` | The difference between reading a claim and checking it. |
| `spring.datasource.username` | A superuser or table owner bypasses row-level security entirely, and every isolation test then passes for the wrong reason. |
