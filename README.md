# TenantLayer

**The tenant isolation layer for Spring Boot + Postgres.**

Your query has no `WHERE tenant_id`. It returns only your tenant's rows anyway.

```java
@WithTenant("acme")
@Test
void oneTenantCannotSeeAnother() {
    assertTenantCannotSee("globex");
}
```

## What it does

Postgres already has Row-Level Security. We don't reinvent it — the database does the
enforcing, which is exactly why this is trustworthy. What TenantLayer does is the wiring
around it, which is where every hand-rolled implementation goes wrong:

- **Sets the tenant on the connection at the right moment**, and **guarantees the reset
  when the connection returns to the pool.** This is the bug. With a pool, sessions are
  reused; leave the variable set and the next request inherits it. Your tests won't catch
  it, because tests don't contend for pooled connections.
- **Gets the tenant there in the first place** — from a header, a subdomain, a path
  segment, or a signed JWT claim — and keeps it alive across `@Async`, `CompletableFuture`,
  virtual threads, `@Scheduled` jobs, outbound HTTP and Kafka. A policy can't help if the
  tenant never reached the thread that opened the connection.
- **Refuses to take the caller's word for it.** Resolution says which tenant a request
  *claims*; membership verification says whether the caller is *entitled* to it.
- **Fails closed.** No resolvable tenant means the request is rejected, never silently
  defaulted to someone.
- **Lets you prove it** — `assertTenantCannotSee(other)` is a real test you can run in CI,
  not a claim in a README.

## Ten minutes

```xml
<dependency>
  <groupId>io.tenantlayer</groupId>
  <artifactId>tenantlayer-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

```properties
tenantlayer.resolvers=JWT,HEADER
tenantlayer.membership.enabled=true
```

Then generate your policies, apply them as a migration, and connect as a role that is not
the table owner. [Getting started](docs/getting-started.md) is the ten-minute version;
[Row-level security](docs/row-level-security.md) is the part to read before you deploy.

## Documentation

[Getting started](docs/getting-started.md) ·
[Row-level security](docs/row-level-security.md) ·
[Isolation strategies](docs/isolation-strategies.md) ·
[Tenant resolution](docs/tenant-resolution.md) ·
[Securing resolution](docs/securing-resolution.md) ·
[Context propagation](docs/context-propagation.md) ·
[Tenant registry](docs/tenant-registry.md) ·
[Testing](docs/testing.md) ·
[Configuration](docs/configuration.md)

## Status

**Pre-alpha. The code is v0.1-complete; the release is not.**

Of the 34 features in the v0.1 roadmap, **30 are built and tested**, and every isolation
claim among them has been mutation-tested — broken deliberately to confirm the test goes
red. The other four are honest about what they are:

| | Status |
|---|---|
| ThreadLocal + **ScopedValue** backing | The storage SPI ships with the ThreadLocal implementation. ScopedValue is a preview API until JDK 25 and shipping it would force `--enable-preview` on every consumer. See [Context storage](docs/context-storage.md). |
| Apache 2.0 core **on Maven Central** | Nothing is published yet. The build produces signed sources and javadoc jars under `-Prelease`; the deploy has not happened. |
| **Docs site** & guides | The guides are written and in `docs/`. There is no hosted site. |
| **Community support** | Issue and PR templates exist. There is no public repository yet, so there is nowhere to file one. |

Everything below is exercised by the suite.

| | Proves |
|---|---|
| `IsolationTest` | The ORM emits no tenant predicate; rows are scoped anyway |
| `PooledConnectionTest` | A recycled connection fails closed, not open |
| `AsyncPropagationTest` | The tenant survives a thread boundary |
| `MembershipVerificationTest` | A token for acme cannot act as globex by setting a header |
| `DiscriminatorStrategyTest` | `@TenantId` scopes a table Postgres is *not* protecting |
| `SchemaGenerationTest` | The generated policy, applied, actually isolates |
| `KafkaPropagationTest` | The tenant survives a broker, and does not linger on the listener |
| `VirtualThreadPropagationTest` | Enabling virtual threads does not drop the tenant |
| `ShippedFixtureTest` | The published test fixtures work outside this repo |

Verified on Java 17 and 21, Hibernate 6.6 and 7.0, Spring Boot 3.5.

## A worked example

[`examples/order-service`](examples/order-service) is an ordinary business service that
consumes TenantLayer as a **published dependency**, not as source — it exists to answer one
question: does this work for somebody who is not TenantLayer?

Grep it for tenancy and there is none. `OrderController` takes no tenant parameter and
reads no header; `OrderRepository` is an empty `JpaRepository` with no `findByTenantId`.
The SQL Hibernate emits has no tenant predicate in it. Isolation holds anyway.

It also earns its keep as a test: it caught a `NoClassDefFoundError` that would have broken
startup for most adopters and that this repo's own 100+ tests structurally could not find,
because a library's optional dependencies are always present on its own test classpath.

## Building

```
export JAVA_HOME=/path/to/jdk-17

mvn test                    # the library, Hibernate 6
mvn test -Phibernate7       # the library, Hibernate 7 + Jakarta Persistence 3.2

mvn install -DskipTests     # then the example, which consumes the artifact
cd examples/order-service && mvn test
```

On JDK 21+ the `jdk21` profile activates automatically and adds the virtual-thread tests.
Tests need Docker — Testcontainers starts Postgres, and Kafka for the messaging tests.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md). One rule matters more than the rest: every isolation
claim is mutation-tested. Break it, watch it fail, then fix it.

## Licence

Apache 2.0.
