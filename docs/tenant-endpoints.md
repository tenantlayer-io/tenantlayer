# Tenant endpoints

The registry could only be driven from Java, which makes onboarding a deployment for anyone
without a signup form. These endpoints make it operable.

```
GET     /actuator/tenants                 every tenant and its status
GET     /actuator/tenants/{tenantId}      one tenant
POST    /actuator/tenants                 onboard a tenant
POST    /actuator/tenants/{tenantId}      change its status
DELETE  /actuator/tenants/{tenantId}      remove the registry row
```

## Turning it on — twice

```properties
management.endpoint.tenants.enabled=true
management.endpoints.web.exposure.include=tenants
```

**Both are required**, and neither on its own does anything. That is deliberate for an
endpoint that can create a tenant: turning it on is a decision somebody makes twice, and
nobody arrives at it by copying a properties file.

## Onboarding

```bash
curl -X POST localhost:8080/actuator/tenants \
     -H 'Content-Type: application/json' \
     -d '{"tenantId":"acme","region":"eu-west-1","datasourceRef":"shard-a"}'
```

```json
{
  "tenantId": "acme",
  "status": "ACTIVE",
  "servable": true,
  "region": "eu-west-1",
  "group": null,
  "datasourceRef": "shard-a",
  "metadata": {}
}
```

Only `tenantId` is required. This runs the full [provisioning sequence](onboarding.md) —
migrations, hooks, then `ACTIVE` — rather than inserting a row. Writing the row directly
would produce a tenant that exists and does not work.

Idempotent, like `onboard` itself: posting an existing active tenant changes nothing.

## Suspending and reactivating

```bash
curl -X POST localhost:8080/actuator/tenants/acme \
     -H 'Content-Type: application/json' -d '{"status":"SUSPENDED"}'
```

`PROVISIONING` is refused here. That status belongs to the provisioning sequence, and
setting it by hand would claim work had happened that had not.

Suspension takes effect immediately for `forEachTenant`. Whether it also refuses requests
depends on your configuration — see [the tenant registry](tenant-registry.md).

## Deleting

```bash
curl -X DELETE localhost:8080/actuator/tenants/acme
```

```json
{"tenantId":"acme","removed":true,"note":"the registry row is gone; this tenant's data is not"}
```

**It removes the registry row and nothing else.** No library should decide to delete a
customer's data, and under row-level security those rows cannot even be identified without a
tenant bound. Suspending is almost always what was meant.

## Securing it

It inherits whatever protects your other management endpoints, which is the main reason it
is an actuator endpoint rather than a controller. If your actuator endpoints are open, so is
this — and this one creates tenants.

The usual arrangement is a separate management port, unreachable from outside:

```properties
management.server.port=9001
management.server.address=127.0.0.1
```

## It is not itself tenant-scoped

`/actuator` is in `tenantlayer.unscoped-paths` by default, so the endpoint that manages
tenants does not need to be one. A request to it carries no tenant header and is not
rejected by strict mode — which is what you want from something that exists to create the
first tenant.

## Without actuator

The endpoint simply does not exist. `spring-boot-actuator-autoconfigure` is an optional
dependency, and a service without it starts exactly as before.

## What this is the beginning of

These endpoints are what a control plane drives. In an estate, one service owns tenant
lifecycle — signup, suspension, the admin screens — and it is that service which exposes
these. The other services do not need them, and mostly should not have them.
