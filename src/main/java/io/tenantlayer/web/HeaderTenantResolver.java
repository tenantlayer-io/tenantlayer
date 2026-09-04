package io.tenantlayer.web;

import io.tenantlayer.core.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Reads the tenant from a request header.
 *
 * Note what this does NOT do: it trusts the header. That is fine behind a gateway that
 * sets it, and unacceptable when the caller can forge it. Feature 52 (membership
 * verification) is what closes that, by checking the authenticated principal actually
 * belongs to the resolved tenant. Until then this is a development-grade resolver and the
 * JWT claim resolver is the one to reach for in production.
 */
public class HeaderTenantResolver implements TenantResolver<HttpServletRequest> {

    public static final String DEFAULT_HEADER = "X-Tenant-ID";

    private final String headerName;

    public HeaderTenantResolver(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public Optional<String> resolve(HttpServletRequest request) {
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    public String headerName() {
        return headerName;
    }
}
