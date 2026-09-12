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

The distinction is the whole thing. In nginx:

```nginx
# Right — whatever the client sent is discarded.
proxy_set_header X-Tenant-ID $tenant_from_jwt;

# Wrong — a client-supplied header survives and is trusted downstream.
proxy_pass_request_headers on;
```

Spring Cloud Gateway, the same idea:

```java
// setRequestHeader replaces. addRequestHeader would append to what the caller sent.
.route("api", r -> r.path("/api/**")
        .filters(f -> f.setRequestHeader("X-Tenant-ID", tenantFromToken()))
        .uri("http://orders"))
```

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

A database-backed version, since that is the common case once tenants and users are real
records rather than claims:

```java
@Bean
TenantMembershipVerifier tenantMembershipVerifier(MembershipRepository memberships) {
    return tenantId -> {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (caller == null || !caller.isAuthenticated()) {
            return false;                 // unauthenticated is not "allowed"
        }
        return memberships.exists(caller.getName(), tenantId);
    };
}
```

The verifier receives only the tenant — the caller comes from the `SecurityContext`, which
Spring Security has populated by the time this runs.

The verifier does not need to check the registry's status column. When a `TenantRegistry`
bean exists (and `tenantlayer.registry.enforce-status` is left on) the filter consults it
after membership and refuses any tenant that is not `ACTIVE` with a 403 — see
[the tenant registry](tenant-registry.md#suspending-a-tenant).

## Proving it

The test that matters is the one where a caller claims a tenant they do not hold:

```java
@Test
void aCallerCannotClaimATenantTheyDoNotBelongTo() {
    // Token is valid, and lists only acme.
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenFor("user-1", List.of("acme")));
    headers.set("X-Tenant-ID", "globex");

    ResponseEntity<String> response = http.exchange(
            "/orders", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode())
            .as("a token for acme reached globex")
            .isEqualTo(HttpStatus.FORBIDDEN);
}
```

Note the assertion is **403 and not an empty list**. If membership is not wired up, this
request succeeds and returns globex's data — and a test asserting "the response is empty"
would pass on a fresh database, which is how this gets shipped.

Then turn membership off and confirm the test fails. If it still passes, it was never
testing membership.

## The three questions, side by side

| | Resolution | Membership | Status |
|---|---|---|---|
| Answers | Which tenant does this request claim? | Is this caller entitled to it? | Is that tenant currently live? |
| Reads | Header, subdomain, path, token claim | The authenticated principal | The tenant registry |
| Fails with | 400 (strict mode) | 403 | 403 |
| Configured by | `tenantlayer.resolvers` | `tenantlayer.membership.enabled` | `tenantlayer.registry.enforce-status`, on by default when a `TenantRegistry` bean exists |
| Enough on its own | Only behind a header-overwriting gateway | — | — |

A public API needs the first two; the third comes with the registry. An internal service behind a trust boundary can get away with the
first, right up until the day it is exposed — which is rarely a decision anyone announces.
