# The isolation checker

At start-up TenantLayer compares what your entities say should be protected against what
Postgres actually enforces, and logs the difference. It warns; it never stops the
application.

```
WARN  isolation check: 2 of 3 findings mean isolation is NOT being enforced
WARN    NOT_ENFORCED  invoices — is tenant-scoped but row-level security is not enabled,
                      so every tenant can read every row
          fix: alter table invoices enable row level security;
WARN    NOT_ENFORCED  connection role — the application connects as a superuser, which
                      bypasses every row-level security policy
          fix: connect as a least-privileged role that does not own these tables
WARN    FRAGILE       orders — does not have FORCE ROW LEVEL SECURITY. It is enforced today
                      only because the connecting role does not own the table
          fix: alter table orders force row level security;
```

On by default:

```properties
tenantlayer.check.enabled=true
```

## What it checks

| Finding | Severity | Means |
|---|---|---|
| Connecting as a superuser | `NOT_ENFORCED` | Every policy is bypassed. Nothing below matters. |
| Table has no row-level security | `NOT_ENFORCED` | Every tenant can read every row of it |
| Row-level security on, no policy | `NOT_ENFORCED` | Returns nothing to anyone — usually read as an application bug |
| Owned by the connecting role, no `FORCE` | `NOT_ENFORCED` | The policy is skipped for this application |
| No `FORCE`, not owned by this role | `FRAGILE` | Enforced today, and one ownership change from not being |
| Entity maps a table not in this schema | `NOTE` | It was not checked, rather than checked and found fine |

The scan uses the same `TenantScopedEntityScanner` the policy generator does, so a table is
"tenant-scoped" here for exactly the reason it would be given a policy.

## Why it only warns

An application that will not start is worse than one with a gap it has told you about, and
a checker that can halt a deployment is a checker somebody disables at 2am. It logs, and the
application starts either way.

If you want a build to fail, ask it yourself:

```java
@Autowired IsolationChecker checker;

@Test
void everyTenantScopedTableIsActuallyProtected() {
    assertThat(checker.check())
            .as("isolation is not enforced on every tenant-scoped table")
            .isEmpty();
}
```

That is a better place for it than start-up: a test failing is a build failing, and nobody
is on a call at the time.

## Adopting an existing application

This is the tool that makes the
[adoption sequence](adopting-in-an-existing-app.md) safe. That sequence has one step where
behaviour changes — switching the application to a least-privileged role — and until then
you have no way to know whether every table is covered.

Run the checker before that step. It reports every table that would still be unprotected
once the policies start applying, while nothing is enforcing yet and nothing can break.

## When it stays quiet

Under `DATABASE_PER_TENANT` the check does not run at all. The pool is the isolation there,
policies would be meaningless, and reporting every table as unprotected would be noise
rather than news. The strategy is asked (`expectsRowLevelSecurity()`) rather than assumed.

It also does nothing when no entity is tenant-scoped, which is what you would want in a
service that does not hold tenant data.

## What it cannot tell you

- **Whether the policy is correct.** It checks that a policy exists, not that its predicate
  is right. A policy comparing the wrong column passes this and fails in production.
- **Anything about native queries.** Row-level security covers them, which is the point of
  enforcing in the database — but the checker cannot inspect SQL your application has not
  run yet.
- **Tables no entity maps.** If nothing in JPA knows about a table, nothing here does either.

For the parts it cannot check, the answer is still a test that reads as one tenant and
proves it cannot see another — see [testing](testing.md).
