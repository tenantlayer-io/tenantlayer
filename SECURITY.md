# Security policy

## Reporting a vulnerability

Please do not open a public issue.

Report privately through [GitHub Security Advisories](https://github.com/tenantlayer-io/tenantlayer/security/advisories/new),
or email **security@tenantlayer.io**.

Please include the version, a description of the isolation failure, and the smallest
reproduction you can manage. You will get an acknowledgement within 3 working days and an
assessment within 10.

## What counts

This library exists to keep one tenant's data away from another. Anything that defeats that
is in scope, including:

- a tenant reading, writing or deleting another tenant's rows
- a tenant being resolved or bound when it should not have been, or from an untrusted source
  that should not have been consulted
- a tenant persisting on a pooled connection, a pooled thread, or a message listener beyond
  the unit of work that set it
- a configuration whose documented meaning is more restrictive than its behaviour

Also in scope: anything that causes isolation to be silently *not applied* while appearing
to be configured — a policy that exists but never runs is the failure mode this project
cares most about.

## What does not

- Connecting the application as a Postgres superuser or table owner without
  `FORCE ROW LEVEL SECURITY`. Both bypass row-level security by design; the documentation
  says so in several places.
- Trusting `X-Tenant-ID` from the public internet with no membership verification. That is
  documented as unsafe outside a gateway that overwrites the header; see
  [docs/securing-resolution.md](docs/securing-resolution.md).
- Code that unwraps a pooled connection to a raw `PgConnection` and issues statements on it.

If you are unsure which side of that line something falls on, report it.

## Supported versions

During 0.x, the latest minor release only.
