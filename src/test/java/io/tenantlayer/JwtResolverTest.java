package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration;
import io.tenantlayer.core.TenantResolver;
import io.tenantlayer.core.TenantResolverChain;
import io.tenantlayer.web.HeaderTenantResolver;
import io.tenantlayer.web.JwtClaimTenantResolver;
import io.tenantlayer.web.TenantFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Feature 4 — the tenant read from a signed claim rather than from something the caller
 * typed.
 *
 * {@link Ordering} is the part that is easy to get wrong and invisible when you do. The
 * resolver reads the SecurityContext, so if the filter still ran at its original
 * near-first position it would find nothing on every request, fall through to the header,
 * and quietly restore exactly the trust-the-client behaviour the claim resolver exists to
 * replace. Nothing would fail; isolation would just be back to convention.
 */
class JwtResolverTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
        claims.forEach(builder::claim);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(builder.build(),
                        List.of(new SimpleGrantedAuthority("SCOPE_read"))));
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        private final JwtClaimTenantResolver resolver = new JwtClaimTenantResolver("tenant_id");
        private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");

        @Test
        @DisplayName("reads the configured claim")
        void readsConfiguredClaim() {
            authenticate(Map.of("tenant_id", "acme"));

            assertThat(resolver.resolve(request)).contains("acme");
        }

        @Test
        @DisplayName("a differently named claim is honoured")
        void honoursCustomClaimName() {
            authenticate(Map.of("https://acme.example/tenant", "globex"));

            assertThat(new JwtClaimTenantResolver("https://acme.example/tenant").resolve(request))
                    .contains("globex");
        }

        @Test
        @DisplayName("no authentication yields no tenant")
        void unauthenticatedYieldsNothing() {
            SecurityContextHolder.clearContext();

            assertThat(resolver.resolve(request)).isEmpty();
        }

        @Test
        @DisplayName("a non-JWT principal yields no tenant")
        void nonJwtPrincipalYieldsNothing() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("user", "pw",
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))));

            assertThat(resolver.resolve(request)).isEmpty();
        }

        @Test
        @DisplayName("a token without the claim yields no tenant, it does not invent one")
        void missingClaimYieldsNothing() {
            authenticate(Map.of("sub", "user-1"));

            assertThat(resolver.resolve(request)).isEmpty();
        }

        @Test
        @DisplayName("in a chain the signed claim outranks the spoofable header")
        void signedClaimOutranksHeader() {
            authenticate(Map.of("tenant_id", "acme"));

            MockHttpServletRequest spoofed = new MockHttpServletRequest("GET", "/orders");
            spoofed.addHeader("X-Tenant-ID", "globex");

            TenantResolver<HttpServletRequest> chain = new TenantResolverChain<>(
                    List.of(resolver, new HeaderTenantResolver("X-Tenant-ID")));

            assertThat(chain.resolve(spoofed))
                    .as("order is precedence; the token must win")
                    .contains("acme");
        }

        @Test
        @DisplayName("the header still answers for unauthenticated paths behind the claim")
        void headerStillAnswersWhenNoToken() {
            SecurityContextHolder.clearContext();

            MockHttpServletRequest headed = new MockHttpServletRequest("GET", "/orders");
            headed.addHeader("X-Tenant-ID", "globex");

            TenantResolver<HttpServletRequest> chain = new TenantResolverChain<>(
                    List.of(resolver, new HeaderTenantResolver("X-Tenant-ID")));

            assertThat(chain.resolve(headed)).contains("globex");
        }
    }

    @Nested
    @DisplayName("filter ordering")
    class Ordering {

        private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantLayerAutoConfiguration.class));

        @Test
        @DisplayName("with header resolution the filter runs before everything")
        void headerResolutionRunsEarly() {
            runner.withPropertyValues("tenantlayer.resolvers=HEADER").run(context ->
                    assertThat(orderOf(context.getBean(FilterRegistrationBean.class)))
                            .as("nothing may touch the database before the tenant is bound")
                            .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 20));
        }

        @Test
        @DisplayName("with JWT resolution the filter moves after Spring Security")
        void jwtResolutionRunsAfterSecurity() {
            runner.withPropertyValues("tenantlayer.resolvers=JWT").run(context ->
                    assertThat(orderOf(context.getBean(FilterRegistrationBean.class)))
                            .as("running before authentication would make the claim invisible")
                            .isGreaterThan(-100)
                            .isLessThan(0));
        }

        @Test
        @DisplayName("membership verification alone also moves the filter after security")
        void membershipVerificationRunsAfterSecurity() {
            runner.withPropertyValues(
                    "tenantlayer.resolvers=HEADER",
                    "tenantlayer.membership.enabled=true").run(context ->
                    assertThat(orderOf(context.getBean(FilterRegistrationBean.class)))
                            .isGreaterThan(-100)
                            .isLessThan(0));
        }

        @Test
        @DisplayName("an explicit filter-order property overrides the derivation")
        void explicitOrderWins() {
            runner.withPropertyValues(
                    "tenantlayer.resolvers=JWT",
                    "tenantlayer.filter-order=42").run(context ->
                    assertThat(orderOf(context.getBean(FilterRegistrationBean.class)))
                            .isEqualTo(42));
        }

        private int orderOf(FilterRegistrationBean<?> registration) {
            return registration.getOrder();
        }
    }
}
