package io.tenantlayer.web;

import io.tenantlayer.core.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Feature 2 — resolve the tenant from the subdomain: acme.app.com -> acme.
 *
 * Two things this deliberately refuses to do. It will not treat a bare host with no
 * subdomain (app.com, localhost) as a tenant, and it will not treat a reserved label
 * (www, api, admin) as one either — otherwise "www.app.com" silently becomes a tenant
 * named www and everyone who hits the marketing site gets an empty application.
 */
public class SubdomainTenantResolver implements TenantResolver<HttpServletRequest> {

    private static final Set<String> RESERVED = Set.of("www", "api", "admin", "app", "static");

    private final String baseDomain;
    private final Set<String> reserved;

    public SubdomainTenantResolver(String baseDomain) {
        this(baseDomain, RESERVED);
    }

    public SubdomainTenantResolver(String baseDomain, Set<String> reserved) {
        this.baseDomain = baseDomain == null ? null : baseDomain.toLowerCase(Locale.ROOT);
        this.reserved = reserved;
    }

    @Override
    public Optional<String> resolve(HttpServletRequest request) {
        String host = request.getServerName();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        host = host.toLowerCase(Locale.ROOT);

        String candidate;
        if (baseDomain != null) {
            if (!host.endsWith("." + baseDomain)) {
                return Optional.empty();
            }
            candidate = host.substring(0, host.length() - baseDomain.length() - 1);
        } else {
            int firstDot = host.indexOf('.');
            if (firstDot <= 0) {
                return Optional.empty();
            }
            candidate = host.substring(0, firstDot);
        }

        // A multi-label prefix (a.b.app.com) is ambiguous; refuse rather than guess.
        if (candidate.isBlank() || candidate.contains(".") || reserved.contains(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }
}
