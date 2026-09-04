# Tenant resolution

```properties
tenantlayer.resolvers=JWT,HEADER,SUBDOMAIN,PATH
```

Order is precedence, first match wins. **List the source you trust most first** — a
spoofable header should never outrank a signed claim.

| Source | Reads | Config |
|---|---|---|
| `JWT` | a claim from a validated token | `tenantlayer.jwt-claim` (default `tenant_id`) |
| `HEADER` | a request header | `tenantlayer.header` (default `X-Tenant-ID`) |
| `SUBDOMAIN` | the first label of the host | `tenantlayer.base-domain` |
| `PATH` | a path segment, `/t/{tenant}/...` | `tenantlayer.path-prefix` (default `/t`) |

## Strict mode

```properties
tenantlayer.strict=true
```

On by default. A request with no resolvable tenant is rejected with 400. The alternative —
carry on with no tenant — yields an empty result set, which reads as "no data" and sends
the caller hunting for a bug in their query rather than in their request.

Paths that legitimately have no tenant are listed rather than guessed:

```properties
tenantlayer.unscoped-paths=/actuator,/error,/login
```

## The subdomain resolver refuses ambiguity

`www.app.com` does not become a tenant named `www`, `app.com` with no subdomain does not
resolve, and a multi-label prefix is refused rather than guessed at. Silently inventing a
tenant is worse than resolving none, because strict mode turns "none" into a clear 400
while an invented one produces a plausible empty page.

## Writing your own

`TenantResolver<S>` is the product's public extension point:

```java
@Bean
TenantResolver<HttpServletRequest> tenantResolver() {
    return request -> Optional.ofNullable(request.getHeader("X-Api-Key"))
            .flatMap(apiKeys::tenantFor);
}
```

Return `Optional.empty()` when your resolver has no opinion, so a chain can fall through.
Define the bean and the autoconfigured chain backs off.
