package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantResolver;
import io.tenantlayer.core.TenantResolverChain;
import io.tenantlayer.web.HeaderTenantResolver;
import io.tenantlayer.web.PathSegmentTenantResolver;
import io.tenantlayer.web.SubdomainTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Features 1, 2, 3 and 6 — the resolvers and their precedence. */
class ResolverTest {

    @Nested
    @DisplayName("feature 2 — subdomain")
    class Subdomain {

        private final TenantResolver<HttpServletRequest> resolver =
                new SubdomainTenantResolver("app.com");

        @Test
        void readsTheSubdomain() {
            assertThat(resolve("acme.app.com")).contains("acme");
        }

        @Test
        @DisplayName("the bare base domain is not a tenant")
        void bareBaseDomainIsNotATenant() {
            assertThat(resolve("app.com")).isEmpty();
        }

        @Test
        @DisplayName("reserved labels are not tenants — www.app.com must not become tenant 'www'")
        void reservedLabelsAreNotTenants() {
            assertThat(resolve("www.app.com")).isEmpty();
            assertThat(resolve("api.app.com")).isEmpty();
        }

        @Test
        @DisplayName("a host on a different domain is not this deployment's tenant")
        void foreignDomainIsIgnored() {
            assertThat(resolve("acme.evil.com")).isEmpty();
        }

        @Test
        @DisplayName("a multi-label prefix is ambiguous, so refuse rather than guess")
        void multiLabelPrefixIsRefused() {
            assertThat(resolve("eu.acme.app.com")).isEmpty();
        }

        @Test
        void hostIsCaseInsensitive() {
            assertThat(resolve("ACME.App.Com")).contains("acme");
        }

        private Optional<String> resolve(String host) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName(host);
            return resolver.resolve(request);
        }
    }

    @Nested
    @DisplayName("feature 3 — path segment")
    class PathSegment {

        private final TenantResolver<HttpServletRequest> resolver = new PathSegmentTenantResolver("/t");

        @Test
        void readsTheSegmentAfterThePrefix() {
            assertThat(resolve("/t/acme/orders")).contains("acme");
            assertThat(resolve("/t/acme")).contains("acme");
        }

        @Test
        @DisplayName("a path that merely starts with the same letters must not match")
        void similarPrefixDoesNotMatch() {
            assertThat(resolve("/tenants/list")).isEmpty();
            assertThat(resolve("/orders")).isEmpty();
        }

        @Test
        void emptySegmentIsNotATenant() {
            assertThat(resolve("/t/")).isEmpty();
        }

        private Optional<String> resolve(String uri) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            return resolver.resolve(request);
        }
    }

    @Nested
    @DisplayName("feature 6 — chain and precedence")
    class Chain {

        private final TenantResolverChain<HttpServletRequest> chain = new TenantResolverChain<>(List.of(
                new SubdomainTenantResolver("app.com"),
                new HeaderTenantResolver("X-Tenant-ID")));

        @Test
        @DisplayName("the earlier resolver wins when both could answer")
        void firstMatchWins() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName("acme.app.com");
            request.addHeader("X-Tenant-ID", "globex");

            assertThat(chain.resolve(request))
                    .as("order is precedence; the subdomain was listed first")
                    .contains("acme");
        }

        @Test
        @DisplayName("falls through to the next resolver when the first has no opinion")
        void fallsThrough() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName("app.com");
            request.addHeader("X-Tenant-ID", "globex");

            assertThat(chain.resolve(request)).contains("globex");
        }

        @Test
        void emptyWhenNobodyCanAnswer() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName("app.com");

            assertThat(chain.resolve(request)).isEmpty();
        }
    }
}
