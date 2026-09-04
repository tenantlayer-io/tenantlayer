# order-service

An ordinary business service. It exists to answer one question: does TenantLayer work for
somebody who is not TenantLayer?

It consumes the library as a **published dependency** from the local Maven repository, not
as source:

```xml
<dependency>
  <groupId>io.tenantlayer</groupId>
  <artifactId>tenantlayer-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

That dependency and two lines of configuration are the only tenancy-related things in the
project.

## The point: grep this codebase for tenancy

`OrderController` takes no tenant parameter, reads no header, and never checks that an
order it fetched belongs to the caller. `OrderRepository` is an empty `JpaRepository` — no
`findByTenantId`, no `@Query`. `Order` maps `tenant_id` as `insertable = false` because
the service never writes it; the column defaults to the tenant on the connection, so the
database stamps ownership.

The SQL Hibernate actually emits, taken from a passing test run:

```sql
insert into orders (amount_cents,customer,item,status) values (?,?,?,?) returning id
select o1_0.id,o1_0.amount_cents,... from orders o1_0
select o1_0.id,o1_0.amount_cents,... from orders o1_0 where o1_0.id=?
```

No tenant column in the insert. No tenant predicate in either select. Isolation still holds.

## What the test proves

`OrderIsolationTest` drives the running service over HTTP:

- acme places an order, globex places an order, on the same endpoint
- `GET /orders` as acme returns acme's order only; as globex, globex's only
- the returned `tenantId` is correct although the service never sent one
- **acme requests globex's order by id and gets 404** — not 403, not the row
- a request with no `X-Tenant-ID` header gets **400**, not an empty list

## Verified by removing the library

Running the same suite with the library's autoconfiguration switched off:

```
mvn test -Dspring.autoconfigure.exclude=io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration

POST /orders            expected 201 CREATED    but was 500 INTERNAL_SERVER_ERROR
GET /orders (no header) expected 400 BAD_REQUEST but was 200 OK
```

The insert fails because nothing supplies the tenant the column defaults from, and the
unauthenticated request is served instead of rejected. The library is doing the work.

## The gap this surfaced, and how it was closed

The first version of this service ended here with a warning:

> The header resolver trusts `X-Tenant-ID`. Anyone who can reach the service can set it.
> That is acceptable behind a gateway that overwrites the header, and unacceptable
> otherwise. Feature 52 (membership verification) and the JWT claim resolver are what
> close it.

Both are now built, and `HeaderSpoofingClosedTest` is the proof. It exercises two
independent defences, because they fail differently and a deployment might only have one:

- **Precedence.** With `tenantlayer.resolvers=JWT,HEADER`, the signed claim outranks the
  header. A request carrying acme's token and `X-Tenant-ID: globex` is served as acme —
  the header is never consulted.
- **Membership.** When the header *is* the resolved source, the claimed tenant is checked
  against the token. acme's bearer asking for globex gets **403**, and the tenant is never
  bound to the context, so no connection ever carries it.

Turning membership verification off makes both of those return **200 OK** with globex's
data. That is the mutation test, and it is why the assertion is worth having.

Note what did **not** change to achieve this: no controller, no repository, no entity. The
service still contains no tenancy code. What changed is two dependencies, one security
filter chain that authenticates and says nothing about tenants, and four lines of
configuration.

## What else is dogfooded here

| Test | Proves |
|---|---|
| `OrderIsolationTest` | Two tenants on one endpoint; 404 not 403 for someone else's order |
| `TenantResolutionTest` | Header, subdomain, path, precedence, strict mode |
| `AsyncPropagationOverHttpTest` | The tenant survives `@Async` inside a real request |
| `LogEnrichmentTest` | Every log line carries the tenant |
| `TestKitDogfoodTest` | `@WithTenant` and `assertTenantCannotSee` from outside the library |
| `HeaderSpoofingClosedTest` | A token for one tenant cannot reach another |
| `ScheduledJobIsolationTest` | `forEachTenant` scopes a nightly job per tenant, and skips suspended ones |

`ScheduledJobIsolationTest` covers the case a request-scoped tenancy layer cannot: work on
a scheduler thread, with no request and therefore no header. Done wrong it is not an error
— every query runs with no tenant, returns nothing, and the job reports success having done
nothing at all. The last test in that class asserts exactly that failure, so the difference
is visible.

## Running the tests

    # from the repository root, publish the library first
    mvn install -DskipTests

    cd examples/order-service
    mvn test

Testcontainers starts Postgres. The service connects as `orders_app`, a role that is
neither superuser nor table owner — a superuser bypasses row-level security entirely, so
connecting as one would leave the policy in place and never applied.
