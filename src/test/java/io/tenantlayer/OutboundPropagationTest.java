package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import feign.RequestTemplate;
import io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration;
import io.tenantlayer.client.TenantPropagatingExchangeFilter;
import io.tenantlayer.client.TenantPropagatingFeignInterceptor;
import io.tenantlayer.client.TenantPropagatingRequestInterceptor;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Feature 16 — the tenant travels with outbound calls.
 *
 * Each client gets the same two assertions: the header is present when a tenant is bound,
 * and <em>absent</em> — not empty — when one is not. The second is the one worth having.
 * Sending {@code X-Tenant-ID: ""} is a statement about the tenant that the receiving
 * service has to interpret; sending nothing is not.
 */
class OutboundPropagationTest {

    private static final String HEADER = "X-Tenant-ID";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("RestTemplate")
    class RestTemplatePropagation {

        private final RestTemplate restTemplate = new RestTemplate();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        RestTemplatePropagation() {
            restTemplate.getInterceptors().add(new TenantPropagatingRequestInterceptor(HEADER));
        }

        @Test
        @DisplayName("sends the current tenant")
        void sendsCurrentTenant() {
            server.expect(requestTo("https://inventory/stock"))
                    .andExpect(header(HEADER, "acme"))
                    .andRespond(withSuccess());

            TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                    restTemplate.getForObject("https://inventory/stock", String.class));

            server.verify();
        }

        @Test
        @DisplayName("sends no tenant header at all when none is bound")
        void sendsNoHeaderWithoutTenant() {
            server.expect(requestTo("https://inventory/stock"))
                    .andExpect(headerDoesNotExist(HEADER))
                    .andRespond(withSuccess());

            restTemplate.getForObject("https://inventory/stock", String.class);

            server.verify();
        }
    }

    @Nested
    @DisplayName("WebClient")
    class WebClientPropagation {

        private final AtomicReference<ClientRequest> captured = new AtomicReference<>();
        private final ExchangeFunction recording = request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        private final TenantPropagatingExchangeFilter filter =
                new TenantPropagatingExchangeFilter(HEADER);

        @Test
        @DisplayName("sends the current tenant")
        void sendsCurrentTenant() {
            ClientRequest request = ClientRequest
                    .create(org.springframework.http.HttpMethod.GET,
                            java.net.URI.create("https://inventory/stock")).build();

            TenantContext.runWithTenant(TenantScope.of("globex"), () ->
                    filter.filter(request, recording).block());

            assertThat(captured.get().headers().getFirst(HEADER)).isEqualTo("globex");
        }

        @Test
        @DisplayName("sends no tenant header at all when none is bound")
        void sendsNoHeaderWithoutTenant() {
            ClientRequest request = ClientRequest
                    .create(org.springframework.http.HttpMethod.GET,
                            java.net.URI.create("https://inventory/stock")).build();

            filter.filter(request, recording).block();

            assertThat(captured.get().headers().containsKey(HEADER)).isFalse();
        }
    }

    @Nested
    @DisplayName("Feign")
    class FeignPropagation {

        private final TenantPropagatingFeignInterceptor interceptor =
                new TenantPropagatingFeignInterceptor(HEADER);

        @Test
        @DisplayName("sends the current tenant")
        void sendsCurrentTenant() {
            RequestTemplate template = new RequestTemplate();

            TenantContext.runWithTenant(TenantScope.of("acme"), () -> interceptor.apply(template));

            assertThat(template.headers().get(HEADER)).containsExactly("acme");
        }

        @Test
        @DisplayName("sends no tenant header at all when none is bound")
        void sendsNoHeaderWithoutTenant() {
            RequestTemplate template = new RequestTemplate();

            interceptor.apply(template);

            assertThat(template.headers()).doesNotContainKey(HEADER);
        }
    }

    @Nested
    @DisplayName("optional dependencies absent")
    class OptionalDependenciesAbsent {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantLayerAutoConfiguration.class));

        /**
         * A regression guard for a real failure. These beans once lived in a single
         * configuration class with per-method @ConditionalOnClass, which does not work:
         * Spring introspects every @Bean method's return type before evaluating the
         * condition, so a bean returning feign.RequestInterceptor threw
         * NoClassDefFoundError in every application without Feign — which is most of them.
         *
         * The library's own test classpath has all the optional dependencies, so nothing
         * here would have caught it without deliberately taking them away.
         */
        @Test
        @DisplayName("the context starts with no Feign on the classpath")
        void startsWithoutFeign() {
            runner.withClassLoader(new FilteredClassLoader("feign.RequestInterceptor"))
                    .run(context -> assertThat(context)
                            .as("an application without Feign must still start")
                            .hasNotFailed()
                            .hasSingleBean(RestTemplateCustomizer.class));
        }

        @Test
        @DisplayName("the context starts with no WebFlux on the classpath")
        void startsWithoutWebFlux() {
            runner.withClassLoader(new FilteredClassLoader(
                            "org.springframework.web.reactive.function.client.WebClient"))
                    .run(context -> assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(RestTemplateCustomizer.class));
        }

        @Test
        @DisplayName("the context starts with neither, which is the common case")
        void startsWithNeither() {
            runner.withClassLoader(new FilteredClassLoader(
                            "feign.RequestInterceptor",
                            "org.springframework.web.reactive.function.client.WebClient"))
                    .run(context -> assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(RestTemplateCustomizer.class)
                            .hasSingleBean(RestClientCustomizer.class));
        }

        @Test
        @DisplayName("the context starts with no Spring Security on the classpath")
        void startsWithoutSpringSecurity() {
            runner.withClassLoader(new FilteredClassLoader(
                            "org.springframework.security.oauth2.jwt.Jwt"))
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("autoconfiguration")
    class Wiring {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantLayerAutoConfiguration.class));

        @Test
        @DisplayName("registers a customizer for each client on the classpath")
        void registersCustomizers() {
            runner.run(context -> assertThat(context)
                    .hasSingleBean(RestTemplateCustomizer.class)
                    .hasSingleBean(RestClientCustomizer.class)
                    .hasSingleBean(feign.RequestInterceptor.class));
        }

        @Test
        @DisplayName("the registered customizer actually installs the interceptor")
        void customizerInstallsInterceptor() {
            runner.run(context -> {
                RestTemplate restTemplate = new RestTemplate();
                context.getBean(RestTemplateCustomizer.class).customize(restTemplate);

                assertThat(restTemplate.getInterceptors())
                        .as("a registered bean that decorates nothing is not propagation")
                        .anyMatch(TenantPropagatingRequestInterceptor.class::isInstance);
            });
        }

        @Test
        @DisplayName("the interceptor honours a custom header name")
        void honoursCustomHeaderName() {
            runner.withPropertyValues("tenantlayer.header=X-Org").run(context -> {
                RestTemplate restTemplate = new RestTemplate();
                context.getBean(RestTemplateCustomizer.class).customize(restTemplate);

                assertThat(restTemplate.getInterceptors())
                        .filteredOn(TenantPropagatingRequestInterceptor.class::isInstance)
                        .first()
                        .extracting(i -> ((TenantPropagatingRequestInterceptor) i).headerName())
                        .isEqualTo("X-Org");
            });
        }
    }
}
