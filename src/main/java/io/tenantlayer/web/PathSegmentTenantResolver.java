package io.tenantlayer.web;

import io.tenantlayer.core.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.util.UrlPathHelper;

/**
 * Feature 3 — resolve the tenant from a path segment: /t/{tenant}/orders -> acme.
 *
 * Matches only when the configured prefix is the start of the path, so /orders and
 * /tenants both fall through to the next resolver rather than being misread.
 */
public class PathSegmentTenantResolver implements TenantResolver<HttpServletRequest> {

    public static final String DEFAULT_PREFIX = "/t";

    private final String prefix;
    private final UrlPathHelper pathHelper = new UrlPathHelper();

    public PathSegmentTenantResolver(String prefix) {
        String normalised = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix.trim();
        if (!normalised.startsWith("/")) {
            normalised = "/" + normalised;
        }
        while (normalised.endsWith("/")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        this.prefix = normalised;
    }

    @Override
    public Optional<String> resolve(HttpServletRequest request) {
        String path = pathHelper.getPathWithinApplication(request);
        if (path == null || !path.startsWith(prefix + "/")) {
            return Optional.empty();
        }
        String remainder = path.substring(prefix.length() + 1);
        int nextSlash = remainder.indexOf('/');
        String candidate = nextSlash < 0 ? remainder : remainder.substring(0, nextSlash);
        return candidate.isBlank() ? Optional.empty() : Optional.of(candidate);
    }

    public String prefix() {
        return prefix;
    }
}
