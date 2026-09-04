## What this changes

<!-- One or two sentences. -->

## Mutation test

<!--
Required for anything touching isolation. What did you break to confirm the test was
actually testing something, and did it go red?

  Broke: removed the SET LOCAL from TenantAwareDataSource
  Result: PooledConnectionTest.recycledConnectionFailsClosed failed as expected
-->

- [ ] I broke the implementation and confirmed the new test fails
- [ ] Every "cannot see the other tenant" assertion is paired with "can see its own rows"
- [ ] Tests connect as a least-privileged role, not a superuser

## Checks

- [ ] `mvn test` passes
- [ ] `mvn test -Phibernate7` passes, if this touches persistence
- [ ] Docs updated, if this changes behaviour or adds a property
