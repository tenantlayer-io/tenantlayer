package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.security.ClaimTenantMembershipVerifier;
import io.tenantlayer.security.TenantMembershipVerifier;
import io.tenantlayer.web.HeaderTenantResolver;
import io.tenantlayer.web.TenantFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Feature 52 — the fix for the one hole order-service's README called out by name:
 * "the header resolver trusts X-Tenant-ID. Anyone who can reach the service can set it."
 *
 * The decisive test is {@link Spoofing#tokenForOneTenantCannotActAsAnother()}. Everything
 * else here supports it.
 */
class MembershipVerificationTest {

    private static final List<String> UNSCOPED = List.of("/actuator", "/error");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static void authenticateWithClaims(Map<String, Object> claims, String... authorities) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
        claims.forEach(builder::claim);
        List<SimpleGrantedAuthority> granted = List.of(authorities).stream()
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(builder.build(), granted));
    }

    @Nested
    @DisplayName("ClaimTenantMembershipVerifier")
    class Verifier {

        private final TenantMembershipVerifier verifier =
                new ClaimTenantMembershipVerifier("tenants");

        @Test
        @DisplayName("a token listing the tenant grants membership")
        void listedTenantIsGranted() {
            authenticateWithClaims(Map.of("tenants", List.of("acme", "umbrella")));

            assertThat(verifier.isMember("acme")).isTrue();
            assertThat(verifier.isMember("umbrella")).isTrue();
        }

        @Test
        @DisplayName("a token not listing the tenant is refused")
        void unlistedTenantIsRefused() {
            authenticateWithClaims(Map.of("tenants", List.of("acme")));

            assertThat(verifier.isMember("globex")).isFalse();
        }

        @Test
        @DisplayName("a single-valued claim works as well as a list")
        void singleValuedClaim() {
            authenticateWithClaims(Map.of("tenants", "acme"));

            assertThat(verifier.isMember("acme")).isTrue();
            assertThat(verifier.isMember("globex")).isFalse();
        }

        @Test
        @DisplayName("a TENANT_ authority grants membership")
        void authorityGrantsMembership() {
            authenticateWithClaims(Map.of("sub", "user-1"), "TENANT_acme");

            assertThat(verifier.isMember("acme")).isTrue();
            assertThat(verifier.isMember("globex")).isFalse();
        }

        @Test
        @DisplayName("no authentication means no membership — absence is not permission")
        void unauthenticatedIsNotAMember() {
            SecurityContextHolder.clearContext();

            assertThat(verifier.isMember("acme")).isFalse();
        }

        @Test
        @DisplayName("a token carrying no tenant claim at all grants nothing")
        void tokenWithoutClaimGrantsNothing() {
            authenticateWithClaims(Map.of("sub", "user-1"));

            assertThat(verifier.isMember("acme"))
                    .as("an unrestricted token must not mean an all-tenants token")
                    .isFalse();
        }

        @Test
        @DisplayName("a non-JWT principal grants nothing through claims")
        void nonJwtPrincipalGrantsNothingThroughClaims() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("user", "pw",
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))));

            assertThat(verifier.isMember("acme")).isFalse();
        }
    }

    @Nested
    @DisplayName("TenantFilter with membership verification")
    class Spoofing {

        private final TenantFilter filter = new TenantFilter(
                new HeaderTenantResolver("X-Tenant-ID"), true, UNSCOPED,
                new ClaimTenantMembershipVerifier("tenants"));

        @Test
        @DisplayName("a token for one tenant cannot act as another by setting the header")
        void tokenForOneTenantCannotActAsAnother() throws Exception {
            authenticateWithClaims(Map.of("tenants", List.of("acme")));

            MockHttpServletResponse response = invoke("globex");

            assertThat(response.getStatus())
                    .as("acme's token claiming to be globex must be refused")
                    .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("the same token acting as its own tenant is allowed through")
        void tokenActingAsItsOwnTenantIsAllowed() throws Exception {
            authenticateWithClaims(Map.of("tenants", List.of("acme")));

            MockHttpServletResponse response = invoke("acme");

            assertThat(response.getStatus())
                    .as("without this, the test above would pass by rejecting everything")
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("a refused request never binds the tenant it asked for")
        void refusedRequestBindsNoTenant() throws Exception {
            authenticateWithClaims(Map.of("tenants", List.of("acme")));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
            request.addHeader("X-Tenant-ID", "globex");
            MockHttpServletResponse response = new MockHttpServletResponse();

            TenantContext.clear();
            filter.doFilter(request, response, new MockFilterChain());

            assertThat(TenantContext.current())
                    .as("rejection must happen before any connection could carry globex")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unauthenticated request is refused even with a valid-looking header")
        void unauthenticatedRequestIsRefused() throws Exception {
            SecurityContextHolder.clearContext();

            assertThat(invoke("acme").getStatus())
                    .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        }

        private MockHttpServletResponse invoke(String tenantHeader) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
            request.addHeader("X-Tenant-ID", tenantHeader);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            return response;
        }
    }
}
