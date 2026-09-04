# Securing resolution

## Resolution is not authorisation

A resolver reports which tenant a request *says* it is for. That is a different question
from whether the caller is *entitled* to that tenant, and shipping only the first is how a
tenancy layer ends up trusting `X-Tenant-ID`.

```
curl -H 'X-Tenant-ID: globex' https://your-api/orders
```

Anyone who can reach the port can send that. If a header is your only resolver and nothing
checks it, you have tenant isolation against accidents and none against people.

This is acceptable in exactly one shape: behind a gateway that **overwrites** the header on
every inbound request (not one that merely sets it when absent). Anywhere else it is not.

## Two defences, and you want both

### Precedence — resolve from a signed claim

```properties
tenantlayer.resolvers=JWT,HEADER
tenantlayer.jwt-claim=tenant_id
```

Order is precedence. The JWT resolver reads a claim from a token Spring Security has
already validated, so a spoofed header is never consulted — the caller silently gets their
own tenant. The header stays in the chain for internal or unauthenticated paths.

### Membership — verify the claim against the caller

```properties
tenantlayer.membership.enabled=true
tenantlayer.membership.claim=tenants
```

When the header *is* the resolved source, the claimed tenant is checked against the
authenticated principal. A token for acme asking for globex gets **403**, and the tenant is
never bound to the context, so no connection ever carries it.

Membership is granted by either a token claim listing the tenants the bearer may act as:

```json
{ "sub": "user-1", "tenant_id": "acme", "tenants": ["acme", "umbrella"] }
```

or a granted authority of the form `TENANT_acme`, for setups that map tenancy into
authorities. A token carrying no tenant claim at all grants **nothing** — the absence of a
restriction is not permission.

## Filter ordering happens automatically

Both features read the `SecurityContext`, which Spring Security populates in its own filter
chain. `TenantFilter` normally runs near-first, so that nothing can touch the database
before a tenant is bound — but that is *before* authentication, where the claim is
invisible.

TenantLayer moves the filter after Spring Security's chain automatically when either
feature is in use. Nothing about that failure would have been loud: the resolver would find
an empty context on every request and fall through to the header, restoring exactly the
behaviour you added it to replace. Override with `tenantlayer.filter-order` if you must.

## Implementing your own

`TenantMembershipVerifier` is a single method. Implement it for mutual TLS, an internal
service token, a database-backed membership table, anything:

```java
@Bean
TenantMembershipVerifier verifier(MembershipRepository memberships) {
    return tenantId -> memberships.currentPrincipalBelongsTo(tenantId);
}
```

Return `false` when you cannot tell. "I do not know" and "yes" must never be the same
answer.
