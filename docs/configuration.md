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
| `tenantlayer.registry.enabled` | `true` | Expose a `TenantRegistry` backed by the application DataSource. |
| `tenantlayer.registry.table` | `tenantlayer_tenants` | Table the registry reads and writes. Validated as a plain SQL identifier. |

## Schema scanning and policy generation

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.schema.tenant-column` | `tenant_id` | Column that marks a table tenant-scoped. |
| `tenantlayer.schema.includes` | *(empty)* | Tables or entity names to treat as tenant-scoped regardless of columns. |
| `tenantlayer.schema.excludes` | *(empty)* | Tables or entity names never to treat as tenant-scoped. |

## Strategies and integrations

| Property | Default | Meaning |
|---|---|---|
| `tenantlayer.strategy` | `ROW_LEVEL_SECURITY` | `ROW_LEVEL_SECURITY` publishes a session-scoped tenant on checkout; `ROW_LEVEL_SECURITY_TRANSACTION_SCOPED` binds with `SET LOCAL` at JDBC transaction start and rejects access outside a transaction; `SCHEMA_PER_TENANT` selects `search_path`. |
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
