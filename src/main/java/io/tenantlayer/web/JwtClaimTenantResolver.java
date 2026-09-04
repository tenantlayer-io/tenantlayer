package io.tenantlayer.web;

import io.tenantlayer.core.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Feature 4 — the tenant comes from a signed token, not from something the caller typed.
 *
 * <p>This is the resolver that makes the others safe to use. A header or a subdomain is a
 * request for a tenant; a claim inside a validated JWT is a statement by the identity
 * provider. Put this first in the chain and the spoofable sources become a fallback for
 * unauthenticated paths rather than the primary answer.
 *
 * <h2>Filter ordering</h2>
 *
 * This reads the {@code SecurityContextHolder}, which Spring Security populates in its own
 * filter chain. {@code TenantFilter} therefore has to run <em>after</em> that chain, not at
 * its usual near-first position, or this resolver sees an empty context and silently falls
 * through to the next one. The autoconfiguration moves the filter automatically when this
 * resolver is in use; {@code TenantLayerProperties#getFilterOrder()} overrides it.
 */
public class JwtClaimTenantResolver implements TenantResolver<HttpServletRequest> {

    public static final String DEFAULT_CLAIM = "tenant_id";

    private final String claimName;

    public JwtClaimTenantResolver(String claimName) {
        this.claimName = claimName == null || claimName.isBlank() ? DEFAULT_CLAIM : claimName;
    }

    @Override
    public Optional<String> resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        String tenant = jwt.getClaimAsString(claimName);
        return tenant == null || tenant.isBlank() ? Optional.empty() : Optional.of(tenant);
    }

    public String claimName() {
        return claimName;
    }
}
