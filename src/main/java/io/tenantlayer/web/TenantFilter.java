package io.tenantlayer.web;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantResolver;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantStatus;
import io.tenantlayer.security.TenantMembershipVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
 *
 * <p>A third question, feature 54, is asked when a {@link TenantRegistry} is configured:
 * <em>is that tenant currently allowed to be served at all</em>. A tenant the registry marks
 * as anything other than {@link TenantStatus#ACTIVE} is refused here, before any
 * connection is bound, which is the same rule {@code forEachTenant} applies when it decides
 * which tenants a scheduled job visits. The answer may be remembered for a short while
 * (see {@link TenantStatusCache}); the registry itself is never wrapped, so the callers
 * that need the truth — provisioning, iteration — keep getting it.
 */
public class TenantFilter extends OncePerRequestFilter {

    private final TenantResolver<HttpServletRequest> resolver;
    private final boolean strict;
    private final List<String> unscopedPaths;
    private final TenantMembershipVerifier membershipVerifier;
    private final TenantStatusCache status;

    public TenantFilter(TenantResolver<HttpServletRequest> resolver, boolean strict,
                        List<String> unscopedPaths) {
        this(resolver, strict, unscopedPaths, null, null);
    }

    public TenantFilter(TenantResolver<HttpServletRequest> resolver, boolean strict,
                        List<String> unscopedPaths, TenantMembershipVerifier membershipVerifier) {
        this(resolver, strict, unscopedPaths, membershipVerifier, null);
    }

    /**
     * Status is checked on every request, uncached. Prefer the constructor taking a TTL
     * when the registry is a database.
     *
     * @param membershipVerifier may be null, in which case a resolved tenant is taken at
     *                           face value
     * @param registry           may be null, in which case the tenant's status is not
     *                           checked and a suspended tenant is served like any other
     */
    public TenantFilter(TenantResolver<HttpServletRequest> resolver, boolean strict,
                        List<String> unscopedPaths, TenantMembershipVerifier membershipVerifier,
                        TenantRegistry registry) {
        this(resolver, strict, unscopedPaths, membershipVerifier, registry, Duration.ZERO);
    }

    /**
     * @param membershipVerifier may be null, in which case a resolved tenant is taken at
     *                           face value
     * @param registry           may be null, in which case the tenant's status is not
     *                           checked and a suspended tenant is served like any other
     * @param statusCacheTtl     how long a tenant's status is trusted before the registry is
     *                           asked again; {@link Duration#ZERO} asks on every request
     */
    public TenantFilter(TenantResolver<HttpServletRequest> resolver, boolean strict,
                        List<String> unscopedPaths, TenantMembershipVerifier membershipVerifier,
                        TenantRegistry registry, Duration statusCacheTtl) {
        this.resolver = resolver;
        this.strict = strict;
        this.unscopedPaths = unscopedPaths;
        this.membershipVerifier = membershipVerifier;
        this.status = registry == null ? null : new TenantStatusCache(registry, statusCacheTtl);
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

        // Feature 54. The status flag has been in the registry since v0.1; this is where it
        // starts to mean something. Same rule as forEachTenant — only ACTIVE is served — so
        // a suspended tenant cannot be skipped by the nightly job yet still answer requests.
        // Also before the tenant is bound, for the same reason as membership. A tenant the
        // registry has never heard of is not refused here: that is a different control
        // (does this tenant exist) and enabling it would turn every deployment that has
        // not yet populated its registry into one that rejects all traffic.
        if (status != null) {
            Optional<TenantStatus> current = status.statusOf(tenant.get());
            if (current.isPresent() && !current.get().isServable()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "Tenant '" + tenant.get() + "' is "
                                + current.get().name().toLowerCase(Locale.ROOT) + ".");
                return;
            }
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
