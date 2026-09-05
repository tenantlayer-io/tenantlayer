# Contributing to TenantLayer

## How to contribute

1. **Open an issue first** for anything beyond a typo. It costs you five minutes and can
   save you a weekend — scope is opinionated here (see below), and it is far kinder to say
   "that belongs in the paid tier" or "that weakens fail-closed" before you write it than
   after.
2. **Fork, branch, and work.** Branch naming is not policed.
3. **Open a pull request** using the template. It asks what you broke to prove the test
   works; that is not a formality, see the next section.
4. **CI must be green** — the full matrix runs JDK 17 and 21, Hibernate 6 and 7, plus a
   build of `examples/order-service` against the packaged artifact.
5. **A maintainer reviews and squash-merges.** Your branch history stays yours; `main`
   stays a readable sequence of changes.

Pull requests are squashed on merge, so you do not need to tidy your history. You do need
every commit signed off.

## Sign your work

Every commit must carry a `Signed-off-by` line:

```
Signed-off-by: Your Name <your.email@example.com>
```

`git commit -s` adds it for you. If you forgot on commits you have already made:

```bash
git rebase --signoff origin/main
git push --force-with-lease
```

This is the [Developer Certificate of Origin](DCO.md) — a statement that you wrote the
code, or otherwise have the right to submit it under Apache 2.0. It is checked by CI.

**There is no CLA, and that is deliberate.** TenantLayer is open core, and open-core
projects usually require a contributor licence agreement so the company can move
contributed code into its proprietary tier. This project's governing rule makes that
unnecessary: *free is correctness, paid is operations, and the free core is never crippled
to sell the paid one.* Free-core code does not migrate into Pro, Pro ships from a separate
repository as a separate artifact, and so there is nothing a CLA would buy that a DCO does
not. Your contribution stays Apache 2.0, and it will not turn up behind a paywall.

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

## What will be rejected

A change can be well written, well tested and useful, and still be declined. This is a
library whose only job is to keep one tenant's data away from another, so the bar is not
"does it work" but "can it fail open".

Expect a "no" if a change:

- **lets data be read with no tenant bound.** No tenant must mean no rows, never all rows.
- **weakens a fail-closed default**, or adds a setting whose *insecure* value is the
  default. Insecure has to be a decision someone typed.
- **adds a non-optional dependency.** Spring Security, Kafka, WebFlux, Feign and
  Testcontainers are all optional and must stay that way; a consumer using header
  resolution and RLS should inherit none of them. There is a test that enforces this by
  removing them from the classpath.
- **adds a test that connects to Postgres as a superuser or table owner.** Both bypass
  row-level security by design, so such a test passes whether or not isolation works —
  including after someone deletes the policy. Use the least-privileged connection from
  `TenantPostgres`.
- **changes public API without saying so** (see below).
- **asserts only the negative.** "Tenant A sees none of B's rows" is satisfied by an empty
  table. Pair it with "A sees its own rows".

None of these are hypothetical. Every one of them is a bug that has been found in this
codebase and caught by a test that was written specifically to catch it.

## Scope

The governing rule for what belongs in the free core:

> **Free is correctness. Paid is operations, compliance and scale.**
> Would a two-person startup need it before they have customers? Free.
> Would a team closing their first large enterprise deal need it? Paid.

The free core is never crippled to sell the paid one. If something is needed to build
tenancy *correctly*, it belongs here, and a PR arguing that is a welcome PR.

## The public API, and what 0.x means

These are what users compile against, and changing them breaks people:

- `TenantResolver<S>` — the extension point for custom resolution
- `TenantContextStorage` — implemented by anyone swapping the backing store
- `TenantScope` — a record, so its components *are* its API
- `TenantMembershipVerifier`
- `TenantRegistry`, `TenantRegistration`, `TenantStatus`
- everything in `io.tenantlayer.test`, which users write their tests against

`0.x` means breaking changes are permitted between minor versions, because schema-per-tenant
and database-per-tenant routing are still to come and will reshape the strategy abstraction.
It does not mean breaking changes are casual. Every one gets a `CHANGELOG.md` entry saying
what broke and how to migrate. The API stabilises at `1.0`, once all three isolation
strategies exist and have stress-tested the abstraction between them.

If your change touches any of the types above, say so in the pull request. It is not a
blocker; it is a thing that has to be written down.

## Style

Match the surrounding code. Comments explain *why*, especially where the obvious
implementation is wrong — most of the comments in this codebase exist because a plausible
alternative has a hole in it, and the next reader deserves to know which one.

## Who decides

TenantLayer is maintained by Suchait Gaurav, who has the final say on scope, design and
what ships. That is worth stating rather than leaving people to infer it: you should know
whose opinion you are arguing with, and that a well-reasoned disagreement is welcome and
may still end in a no.

Decisions that affect the free/paid boundary are made against the published placement test
and nothing else. If a decision looks inconsistent with it, say so in an issue — that is a
fair challenge, and the rule exists precisely so it can be held up against.

## Reporting a security issue

Do not open an issue. See [SECURITY.md](SECURITY.md).
