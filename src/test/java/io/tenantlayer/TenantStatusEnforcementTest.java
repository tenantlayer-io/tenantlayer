package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantRegistryException;
import io.tenantlayer.registry.TenantStatus;
import io.tenantlayer.scheduling.TenantTasks;
import io.tenantlayer.web.HeaderTenantResolver;
import io.tenantlayer.web.TenantFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Feature 54 — the registry's status column starts refusing requests.
 *
 * <p>{@link Consistency#filterAndForEachTenantAgreeOnWhoIsServed()} is the one that
 * matters. Two places decide whether a tenant is live — the filter for requests, the
 * iteration helper for jobs — and a suspension that only one of them honours is a tenant
 * that is off for their users and on for the nightly report. Everything else here supports
 * it, or pins down the edges: a tenant the registry has never heard of is not refused, and a
 * registry that cannot answer does not fail open.
 */
class TenantStatusEnforcementTest {

    private static final List<String> UNSCOPED = List.of("/actuator", "/error");

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static InMemoryRegistry registryWith(String active, String suspended) {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.save(TenantRegistration.of(active));
        registry.save(new TenantRegistration(
                suspended, TenantStatus.SUSPENDED, null, null, null, Map.of()));
        return registry;
    }

    private static MockHttpServletResponse invoke(TenantFilter filter, String tenantHeader)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("X-Tenant-ID", tenantHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Nested
    @DisplayName("TenantFilter with a registry")
    class Filter {

        private final InMemoryRegistry registry = registryWith("acme", "globex");
        private final TenantFilter filter = new TenantFilter(
                new HeaderTenantResolver("X-Tenant-ID"), true, UNSCOPED, null, registry);

        @Test
        @DisplayName("a suspended tenant is refused with 403")
        void suspendedTenantIsRefused() throws Exception {
            MockHttpServletResponse response = invoke(filter, "globex");

            assertThat(response.getStatus())
                    .as("globex is suspended and must not be served")
                    .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            assertThat(response.getErrorMessage()).contains("globex").contains("suspended");
        }

        @Test
        @DisplayName("an active tenant is served")
        void activeTenantIsServed() throws Exception {
            assertThat(invoke(filter, "acme").getStatus())
                    .as("without this, the test above would pass by rejecting everything")
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("a refused request never binds the tenant, and never reaches the chain")
        void refusedRequestBindsNoTenant() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
            request.addHeader("X-Tenant-ID", "globex");
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(TenantContext.current())
                    .as("rejection must happen before any connection could carry globex")
                    .isEmpty();
            assertThat(chain.getRequest())
                    .as("nothing downstream may run for a suspended tenant")
                    .isNull();
        }

        @Test
        @DisplayName("reactivating a tenant takes effect on the next request")
        void reactivationTakesEffectImmediately() throws Exception {
            assertThat(invoke(filter, "globex").getStatus())
                    .isEqualTo(HttpServletResponse.SC_FORBIDDEN);

            registry.save(TenantRegistration.of("globex"));

            assertThat(invoke(filter, "globex").getStatus())
                    .as("the filter reads the registry, it does not remember an answer")
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("a tenant the registry does not know is not refused by this check")
        void unknownTenantIsNotRefusedHere() throws Exception {
            // Existence is a different control from status. Refusing unknown tenants would
            // turn every deployment that has not populated its registry into one that
            // rejects all traffic, so this test pins the boundary rather than leaving it
            // to be discovered.
            assertThat(invoke(filter, "initech").getStatus())
                    .isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("a registry that cannot answer does not fail open")
        void registryFailureIsNotFailOpen() {
            TenantRegistry broken = new InMemoryRegistry() {
                @Override
                public Optional<TenantRegistration> find(String tenantId) {
                    throw new TenantRegistryException("registry unavailable", null);
                }
            };
            TenantFilter filter = new TenantFilter(
                    new HeaderTenantResolver("X-Tenant-ID"), true, UNSCOPED, null, broken);

            assertThatThrownBy(() -> invoke(filter, "globex"))
                    .as("an unanswerable status question must not become a served request")
                    .isInstanceOf(TenantRegistryException.class);
            assertThat(TenantContext.current()).isEmpty();
        }

        @Test
        @DisplayName("without a registry the status is not checked, as before")
        void withoutRegistryNothingChanges() throws Exception {
            TenantFilter filter = new TenantFilter(
                    new HeaderTenantResolver("X-Tenant-ID"), true, UNSCOPED);

            assertThat(invoke(filter, "globex").getStatus())
                    .as("a consumer with no registry inherits no new behaviour")
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
    }

    @Nested
    @DisplayName("consistency with forEachTenant")
    class Consistency {

        @Test
        @DisplayName("the filter serves exactly the tenants forEachTenant visits")
        void filterAndForEachTenantAgreeOnWhoIsServed() throws Exception {
            InMemoryRegistry registry = new InMemoryRegistry();
            registry.save(TenantRegistration.of("acme"));
            registry.save(new TenantRegistration(
                    "globex", TenantStatus.SUSPENDED, null, null, null, Map.of()));
            registry.save(TenantRegistration.of("initech"));

            TenantFilter filter = new TenantFilter(
                    new HeaderTenantResolver("X-Tenant-ID"), true, UNSCOPED, null, registry);
            List<String> served = new ArrayList<>();
            for (TenantRegistration t : registry.findAll()) {
                if (invoke(filter, t.tenantId()).getStatus() == HttpServletResponse.SC_OK) {
                    served.add(t.tenantId());
                }
            }

            List<String> visited = new ArrayList<>();
            new TenantTasks(registry).forEachTenant(visited::add);

            assertThat(served)
                    .as("a tenant off for requests but on for jobs, or the reverse, is a bug")
                    .containsExactlyElementsOf(visited)
                    .containsExactly("acme", "initech");
        }

        @Test
        @DisplayName("within the status cache TTL only the filter lags; the registry, "
                + "forEachTenant and provisioning read the truth")
        void withinTheTtlOnlyTheFilterLags() throws Exception {
            // The cache is the filter's own. It is not a decorator around the registry bean,
            // because TenantProvisioning.onboard reads the same row to stay idempotent and a
            // stale ACTIVE there would re-run every hook on a retried signup. So: the hot,
            // per-request path may be a few seconds behind; everything else must not be.
            InMemoryRegistry registry = new InMemoryRegistry();
            registry.save(TenantRegistration.of("acme"));
            TenantFilter filter = new TenantFilter(
                    new HeaderTenantResolver("X-Tenant-ID"), true, UNSCOPED, null, registry,
                    Duration.ofHours(1));

            assertThat(invoke(filter, "acme").getStatus()).isEqualTo(HttpServletResponse.SC_OK);

            registry.save(new TenantRegistration(
                    "acme", TenantStatus.SUSPENDED, null, null, null, Map.of()));

            assertThat(invoke(filter, "acme").getStatus())
                    .as("inside the TTL the filter still serves — the documented trade-off")
                    .isEqualTo(HttpServletResponse.SC_OK);

            assertThat(registry.find("acme")).get()
                    .extracting(TenantRegistration::status)
                    .as("the registry itself was never wrapped; find() tells the truth")
                    .isEqualTo(TenantStatus.SUSPENDED);
            List<String> visited = new ArrayList<>();
            new TenantTasks(registry).forEachTenant(visited::add);
            assertThat(visited).as("iteration reads the registry, not the filter's cache").isEmpty();
        }
    }

    @Nested
    @DisplayName("autoconfiguration")
    class Wiring {

        private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantLayerAutoConfiguration.class));

        @Test
        @DisplayName("a registry bean is enough — the filter enforces status without opting in")
        void registryBeanIsPickedUpByTheFilter() {
            runner.withUserConfiguration(RegistryConfig.class).run(context -> {
                TenantFilter filter = filterIn(context.getBean(FilterRegistrationBean.class));

                assertThat(invoke(filter, "globex").getStatus())
                        .as("the autoconfigured filter must see the registry")
                        .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
                assertThat(invoke(filter, "acme").getStatus())
                        .isEqualTo(HttpServletResponse.SC_OK);
            });
        }

        @Test
        @DisplayName("tenantlayer.registry.enforce-status=false keeps the registry and drops the check")
        void enforceStatusOffSkipsTheCheck() {
            runner.withUserConfiguration(RegistryConfig.class)
                    .withPropertyValues("tenantlayer.registry.enforce-status=false")
                    .run(context -> {
                        assertThat(context).as("the registry bean is unaffected by the switch")
                                .hasSingleBean(TenantRegistry.class);
                        TenantFilter filter = filterIn(context.getBean(FilterRegistrationBean.class));

                        assertThat(invoke(filter, "globex").getStatus())
                                .as("off means served, as before feature 54")
                                .isEqualTo(HttpServletResponse.SC_OK);
                    });
        }

        @Test
        @DisplayName("the status lookup is cached by default, and the registry bean is not wrapped")
        void statusIsCachedByDefaultWithoutWrappingTheRegistry() {
            runner.withUserConfiguration(RegistryConfig.class).run(context -> {
                TenantRegistry registry = context.getBean(TenantRegistry.class);
                assertThat(registry)
                        .as("provisioning and iteration get the bean as defined, no decorator")
                        .isInstanceOf(InMemoryRegistry.class);
                TenantFilter filter = filterIn(context.getBean(FilterRegistrationBean.class));

                assertThat(invoke(filter, "acme").getStatus()).isEqualTo(HttpServletResponse.SC_OK);
                registry.save(new TenantRegistration(
                        "acme", TenantStatus.SUSPENDED, null, null, null, Map.of()));

                assertThat(invoke(filter, "acme").getStatus())
                        .as("the default TTL is thirty seconds, not zero, so this is still cached")
                        .isEqualTo(HttpServletResponse.SC_OK);
            });
        }

        @Test
        @DisplayName("a zero TTL asks the registry on every request")
        void zeroTtlDisablesTheCache() {
            runner.withUserConfiguration(RegistryConfig.class)
                    .withPropertyValues("tenantlayer.registry.status-cache-ttl=0s")
                    .run(context -> {
                        TenantRegistry registry = context.getBean(TenantRegistry.class);
                        TenantFilter filter = filterIn(context.getBean(FilterRegistrationBean.class));

                        assertThat(invoke(filter, "acme").getStatus()).isEqualTo(HttpServletResponse.SC_OK);
                        registry.save(new TenantRegistration(
                                "acme", TenantStatus.SUSPENDED, null, null, null, Map.of()));

                        assertThat(invoke(filter, "acme").getStatus())
                                .as("no cache: the suspension is seen on the very next request")
                                .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
                    });
        }

        @Test
        @DisplayName("with no registry bean the filter still starts and serves")
        void noRegistryBeanStillStarts() {
            runner.run(context -> {
                TenantFilter filter = filterIn(context.getBean(FilterRegistrationBean.class));

                assertThat(invoke(filter, "globex").getStatus())
                        .isEqualTo(HttpServletResponse.SC_OK);
            });
        }

        @SuppressWarnings("unchecked")
        private static TenantFilter filterIn(FilterRegistrationBean<?> registration) {
            return ((FilterRegistrationBean<TenantFilter>) registration).getFilter();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RegistryConfig {
        @Bean
        TenantRegistry tenantRegistry() {
            return registryWith("acme", "globex");
        }
    }

    /**
     * Enough registry to test the filter without a database. Ordered by tenant id, like
     * the JDBC one, so {@code activeTenantIds()} is deterministic.
     */
    static class InMemoryRegistry implements TenantRegistry {

        private final Map<String, TenantRegistration> rows = new TreeMap<>();

        @Override
        public Optional<TenantRegistration> find(String tenantId) {
            return Optional.ofNullable(rows.get(tenantId));
        }

        @Override
        public List<TenantRegistration> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public List<String> activeTenantIds() {
            return rows.values().stream()
                    .filter(TenantRegistration::isActive)
                    .map(TenantRegistration::tenantId)
                    .toList();
        }

        @Override
        public void save(TenantRegistration registration) {
            rows.put(registration.tenantId(), registration);
        }

        @Override
        public boolean delete(String tenantId) {
            return rows.remove(tenantId) != null;
        }
    }
}
