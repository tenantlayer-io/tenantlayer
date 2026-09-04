package io.tenantlayer.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The default {@link TenantMembershipVerifier}: the token says which tenants its bearer
 * may act as, and nothing else is consulted.
 *
 * <p>Two shapes are accepted, because identity providers disagree:
 * <ul>
 *   <li>a claim holding one tenant id or a list of them ({@code tenants} by default), and</li>
 *   <li>a granted authority of the form {@code TENANT_acme}, for setups that map tenancy
 *       into authorities rather than raw claims.</li>
 * </ul>
 *
 * <p>An unauthenticated request is not a member of anything. Neither is one whose token
 * carries no tenant claim at all — the absence of a restriction is not permission, and
 * treating it as such would reopen precisely the hole this class exists to close.
 */
public class ClaimTenantMembershipVerifier implements TenantMembershipVerifier {

    public static final String DEFAULT_CLAIM = "tenants";
    public static final String AUTHORITY_PREFIX = "TENANT_";

    private final String claimName;

    public ClaimTenantMembershipVerifier(String claimName) {
        this.claimName = claimName == null || claimName.isBlank() ? DEFAULT_CLAIM : claimName;
    }

    @Override
    public boolean isMember(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return claimGrants(authentication, tenantId) || authorityGrants(authentication, tenantId);
    }

    private boolean claimGrants(Authentication authentication, String tenantId) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        Object claim = jwt.getClaim(claimName);
        if (claim instanceof String single) {
            return single.equals(tenantId);
        }
        if (claim instanceof Collection<?> many) {
            return many.stream().filter(Objects::nonNull)
                    .map(Object::toString)
                    .anyMatch(tenantId::equals);
        }
        return false;
    }

    private boolean authorityGrants(Authentication authentication, String tenantId) {
        String wanted = AUTHORITY_PREFIX + tenantId;
        List<GrantedAuthority> authorities = List.copyOf(authentication.getAuthorities());
        return authorities.stream().map(GrantedAuthority::getAuthority).anyMatch(wanted::equals);
    }
}
