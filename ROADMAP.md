# Roadmap

Work lives in **[issues](https://github.com/tenantlayer-io/tenantlayer/issues)**, grouped by
milestone. This file is the context around them; the issues are the actual list.

| Milestone | Theme | Issues |
|---|---|---|
| **[v0.2](https://github.com/tenantlayer-io/tenantlayer/milestone/1)** | Schema-per-tenant routing, reactive and batch propagation, registry lifecycle, migrations, caching | 16 |
| **[v0.3](https://github.com/tenantlayer-io/tenantlayer/milestone/2)** | Database-per-tenant routing and the configuration-driven strategy switch | 4 |
| **[v0.4](https://github.com/tenantlayer-io/tenantlayer/milestone/3)** | Isolation checker, tenant-aware health, the read-only dashboard | 7 |

**[Good first issues](https://github.com/tenantlayer-io/tenantlayer/labels/good%20first%20issue)**
are small and self-contained. **[Help wanted](https://github.com/tenantlayer-io/tenantlayer/labels/help%20wanted)**
is everything unclaimed, which is currently all of it.

Comment on an issue before you start, so two people don't build the same thing.

## Shipped

**[0.1.0](https://github.com/tenantlayer-io/tenantlayer/releases/tag/v0.1.0)** — resolution
from header, subdomain, path and JWT claim; membership verification; leak-proof RLS wiring
and policy generation; the Hibernate discriminator; propagation across `@Async`,
`CompletableFuture`, virtual threads, scheduled jobs, outbound HTTP and Kafka; the tenant
registry; and the testing kit. See the [changelog](CHANGELOG.md).

## What the milestones are actually for

**v0.2 is breadth.** Most of it is additive — a resolver here, a runner there — and most of
it can be worked on in parallel by different people without colliding.

**v0.3 is the one that matters.** Database-per-tenant routing and the strategy switch
together decide the shape of the isolation abstraction, and that abstraction is what
everything else hangs off. It is also the most likely source of a breaking API change, which
is why `1.0` comes after it and not before.

**v0.4 is the things you want once it is running in production** — knowing a policy is
missing before a customer finds out, and being able to see what the library thinks is going
on.

Two issues in v0.2 are more urgent than their milestone suggests. **Tenant-scoped cache
keys** (#13) is a hole straight through every other isolation layer: a cache hit never
consults the database, so row-level security cannot help. **Schema-per-tenant routing** (#3)
carries the same `search_path` leak risk that the connection wiring was built to prevent.

## What is not on this list

TenantLayer is open core. The roadmap above is the **free core**, and the rule that decides
what belongs here is published in [CONTRIBUTING.md](CONTRIBUTING.md):

> Free is correctness. Paid is operations, compliance and scale.
> Would a two-person startup need it before they have customers? Free.
> Would a team closing their first large enterprise deal need it? Paid.

The free core is never crippled to sell the paid one. If something is needed to build
tenancy *correctly* and it is not here, that is an omission worth an issue — say so, and
the rule is what the argument gets held up against.

Deliberately out of scope entirely: cross-region replication, active-active, and
failover/DR orchestration. Those belong to the database platform (Aurora Global, Azure
geo-replication, Cloud SQL). TenantLayer stays region-aware — routing, placement, policy —
and never becomes a replication control plane.

## Versioning

`0.x` permits breaking changes between minor versions, because the isolation abstraction is
not finished. Every break gets a [changelog](CHANGELOG.md) entry with a migration note. The
API stabilises at `1.0`, once all three isolation strategies exist and have stress-tested it
between them.
