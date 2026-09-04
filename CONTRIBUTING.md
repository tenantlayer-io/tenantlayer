# Contributing to TenantLayer

## The one rule that is not negotiable

**Every isolation claim is mutation-tested.** After a test goes green, break the
implementation and confirm the test goes red. A pull request that adds an isolation
assertion should say, in its description, what you broke and that the test caught it.

This is a security library, and the failure mode is a test that passes for the wrong
reason. Real examples from this codebase:

- a `BUILD SUCCESS` with zero tests executed, because Surefire skips `*IT` class names
- an `@Async` test that would have passed if the work never left the calling thread
- an `assertTenantCannotSee` that passed with the library switched off entirely, because
  "cannot see" is trivially satisfied when you can see nothing at all
- an entity scanner that silently returned an empty list, so "no tables are unprotected"
  and "we found no tables" looked identical

Practical consequences:

- Pair every "cannot see the other tenant" assertion with "can see its own rows".
- Seed test data on the privileged connection; the application connection is subject to
  the policy.
- Never let the application connect as a Postgres superuser in tests. Superusers bypass RLS
  outright, so the policy would be in place and never applied.

## Building

```
export JAVA_HOME=/path/to/jdk-17
mvn test                    # Hibernate 6, JDK 17 baseline
mvn test -Phibernate7       # the same suite against Hibernate 7 + Jakarta Persistence 3.2
```

On JDK 21 or newer the `jdk21` profile activates automatically and additionally compiles
and runs `src/test/java21`, which covers virtual threads. The library itself targets Java
17; those tests live apart because the APIs they exercise do not exist below 21.

Tests need Docker for Testcontainers (Postgres, and Kafka for the messaging tests).

## Scope

The governing rule for what belongs in the free core:

> **Free is correctness. Paid is operations, compliance and scale.**
> Would a two-person startup need it before they have customers? Free.
> Would a team closing their first large enterprise deal need it? Paid.

The free core is never crippled to sell the paid one. If something is needed to build
tenancy *correctly*, it belongs here, and a PR arguing that is a welcome PR.

## Style

Match the surrounding code. Comments explain *why*, especially where the obvious
implementation is wrong — most of the comments in this codebase exist because a plausible
alternative has a hole in it, and the next reader deserves to know which one.

## Reporting a security issue

Do not open an issue. See [SECURITY.md](SECURITY.md).
