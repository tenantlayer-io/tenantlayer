# TenantLayer documentation

Start with [Getting started](getting-started.md). Read [Row-level security](row-level-security.md)
before you deploy anything.

| Guide | What it answers |
|---|---|
| [Getting started](getting-started.md) | Adding the dependency and getting isolation in ten minutes |
| [Row-level security](row-level-security.md) | How isolation is actually enforced, and the three mistakes that quietly break it |
| [Isolation strategies](isolation-strategies.md) | Discriminator column vs RLS, and why you want both |
| [Tenant resolution](tenant-resolution.md) | Headers, subdomains, paths, JWT claims, and precedence |
| [Securing resolution](securing-resolution.md) | Why a header alone is not enough, and how to close that |
| [Context propagation](context-propagation.md) | @Async, CompletableFuture, virtual threads, scheduled jobs, HTTP, Kafka |
| [The tenant registry](tenant-registry.md) | Who your tenants are, and running work for each of them |
| [Caching](caching.md) | The one hole row-level security cannot cover, and how it is closed |
| [Testing](testing.md) | Fixtures, assertions, and how to tell a real isolation test from a vacuous one |
| [Context storage](context-storage.md) | ThreadLocal today, ScopedValue later |
| [Configuration reference](configuration.md) | Every property |
| [Architecture](architecture.md) | How the pieces fit together, and why the obvious alternatives are wrong |
| [Adopting in an existing app](adopting-in-an-existing-app.md) | Getting there from a running system, without a flag day |
| [Troubleshooting](troubleshooting.md) | It returns nothing · the policy is not applying · the tenant is null |

## The one-paragraph version

Postgres already has row-level security. TenantLayer does not reinvent it — the database
does the enforcing, which is exactly why it is trustworthy. What TenantLayer does is the
wiring around it: getting the tenant onto the connection at the right moment, guaranteeing
it is cleared when the connection returns to the pool, carrying it across every thread and
network boundary in between, failing closed when it is absent, and giving you a test that
proves all of that rather than a README that claims it.
