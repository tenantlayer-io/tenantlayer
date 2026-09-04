package io.tenantlayer.web;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantResolver;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.security.TenantMembershipVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Establishes the tenant for the duration of one request, and takes it away afterwards.
 *
 * The finally block is not optional. Servlet containers pool their worker threads, so a
 * request that leaves its tenant behind hands it to whoever the container serves next on
 * that thread.
 *
 * <p>Two questions get asked here, and they are not the same question. <em>Which</em>
 * tenant is this request for is the resolver's job. <em>May this caller act as that
 * tenant</em> is feature 52's, and is asked only when a {@link TenantMembershipVerifier}
 * is configured. Without one, a resolved tenant is taken at face value — correct for a
 * service behind a gateway that overwrites the header, and stated plainly in the docs so
 * nobody deploys it anywhere else by accident.
 */
public class TenantFilter extends OncePerRequestFilter {

    private final TenantResolver<HttpServletRequest> resolver;
    private final boolean strict;
    private final List<String> unscopedPaths;
    private final TenantMembershipVerifier membershipVerifier;

    public TenantFilter(TenantResolver<HttpServletRequest> resolver, boolean strict,
                        List<String> unscopedPaths) {
        this(resolver, strict, unscopedPaths, null);
    }

    public TenantFilter(TenantResolver<HttpServletRequest> resolver, boolean strict,
                        List<String> unscopedPaths, TenantMembershipVerifier membershipVerifier) {
        this.resolver = resolver;
        this.strict = strict;
        this.unscopedPaths = unscopedPaths;
        this.membershipVerifier = membershipVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (isUnscoped(request)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<String> tenant = resolver.resolve(request);

        if (tenant.isEmpty()) {
            if (strict) {
                // Fail closed, and say so. The alternative — carry on with no tenant —
                // yields an empty result set, which reads as "no data" and sends the
                // caller hunting for a bug in their query.
                response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                        "No tenant could be resolved for this request.");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // Feature 52. 403 rather than 404: the tenant exists and the caller is
        // authenticated, they are simply not entitled to it. Note this runs before the
        // tenant is ever bound to the context, so a refused request never reaches a
        // connection carrying someone else's tenant.
        if (membershipVerifier != null && !membershipVerifier.isMember(tenant.get())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Not a member of tenant '" + tenant.get() + "'.");
            return;
        }

        TenantScope previous = TenantContext.current().orElse(null);
        TenantContext.enter(TenantScope.of(tenant.get()));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.exit(previous);
        }
    }

    private boolean isUnscoped(HttpServletRequest request) {
        String path = new UrlPathHelper().getPathWithinApplication(request);
        return unscopedPaths.stream().anyMatch(path::startsWith);
    }
}
