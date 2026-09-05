## What this changes

<!-- One or two sentences. Link the issue if there is one. -->

## Mutation test

<!--
Required for anything touching isolation. What did you break to confirm the test was
actually testing something, and did it go red?

  Broke: removed the set_config call from TenantAwareDataSource
  Result: PooledConnectionTest.recycledConnectionFailsClosed failed as expected
-->

- [ ] I broke the implementation and confirmed the new test fails
- [ ] Every "cannot see the other tenant" assertion is paired with "can see its own rows"
- [ ] Tests connect as a least-privileged role, not a superuser

## Public API

- [ ] This does **not** change `TenantResolver`, `TenantContextStorage`, `TenantScope`,
      `TenantMembershipVerifier`, `TenantRegistry`, or anything in `io.tenantlayer.test`
- [ ] …or it does, and I have described the break and the migration below

<!-- If it changes public API, say what breaks and how a user migrates. -->

## Checks

- [ ] All commits are signed off (`git commit -s`) — see [DCO.md](../DCO.md)
- [ ] `mvn test` passes
- [ ] `mvn test -Phibernate7` passes, if this touches persistence
- [ ] No new non-optional dependency
- [ ] Docs updated, if this changes behaviour or adds a property
