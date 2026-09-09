package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.registry.TenantProvisioning;
import io.tenantlayer.registry.TenantProvisioningException;
import io.tenantlayer.registry.TenantProvisioningHook;
import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Features 67 and 53 — onboarding, and the hooks it runs. */
class TenantProvisioningTest {

    /** An in-memory registry: what is under test is the ordering, not JDBC. */
    static class InMemoryRegistry implements TenantRegistry {
        final Map<String, TenantRegistration> rows = new LinkedHashMap<>();
        final List<TenantStatus> statusHistory = new ArrayList<>();

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
            return rows.values().stream().filter(TenantRegistration::isActive)
                    .map(TenantRegistration::tenantId).toList();
        }

        @Override
        public void save(TenantRegistration registration) {
            rows.put(registration.tenantId(), registration);
            statusHistory.add(registration.status());
        }

        @Override
        public boolean delete(String tenantId) {
            return rows.remove(tenantId) != null;
        }
    }

    /** Records the tenant bound while it ran, which is the property that matters. */
    static class RecordingHook implements TenantProvisioningHook {
        final List<String> boundTenants = new ArrayList<>();
        private final int order;
        private final boolean fail;

        RecordingHook(int order, boolean fail) {
            this.order = order;
            this.fail = fail;
        }

        @Override
        public void onTenantCreated(String tenantId) {
            boundTenants.add(TenantContext.current().map(s -> s.subject()).orElse("<none>"));
            if (fail) {
                throw new IllegalStateException("seeding failed");
            }
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public String name() {
            return "hook-" + order;
        }
    }

    private InMemoryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryRegistry();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a tenant is PROVISIONING before it is ACTIVE, never the other way round")
    void statusMovesThroughProvisioning() {
        new TenantProvisioning(registry, null, List.of()).onboard("acme");

        assertThat(registry.statusHistory)
                .as("a tenant that is ACTIVE before its hooks have run is briefly live and broken")
                .containsExactly(TenantStatus.PROVISIONING, TenantStatus.ACTIVE);
    }

    @Test
    @DisplayName("hooks run with the new tenant bound, so seeding needs no tenant parameter")
    void hooksRunWithTheTenantBound() {
        RecordingHook hook = new RecordingHook(0, false);
        new TenantProvisioning(registry, null, List.of(hook)).onboard("acme");

        assertThat(hook.boundTenants)
                .as("a hook writing seed data with no tenant bound fails the policy")
                .containsExactly("acme");
    }

    @Test
    @DisplayName("hooks run in order, lowest first")
    void hooksRunInOrder() {
        List<String> ran = new ArrayList<>();
        TenantProvisioningHook second = named("second", 10, ran);
        TenantProvisioningHook first = named("first", 1, ran);

        new TenantProvisioning(registry, null, List.of(second, first)).onboard("acme");

        assertThat(ran).containsExactly("first", "second");
    }

    @Test
    @DisplayName("a failing hook leaves the tenant PROVISIONING, not ACTIVE and not absent")
    void aFailingHookLeavesAnUnambiguousState() {
        TenantProvisioning provisioning =
                new TenantProvisioning(registry, null, List.of(new RecordingHook(0, true)));

        assertThatThrownBy(() -> provisioning.onboard("acme"))
                .isInstanceOf(TenantProvisioningException.class)
                .hasMessageContaining("hook-0")
                .hasMessageContaining("PROVISIONING");

        assertThat(registry.find("acme")).isPresent();
        assertThat(registry.find("acme").get().status()).isEqualTo(TenantStatus.PROVISIONING);
        assertThat(registry.activeTenantIds())
                .as("a half-created tenant must not be served or iterated")
                .isEmpty();
    }

    @Test
    @DisplayName("a later hook does not run once an earlier one has failed")
    void provisioningStopsAtTheFirstFailure() {
        List<String> ran = new ArrayList<>();
        TenantProvisioningHook failing = new RecordingHook(1, true);
        TenantProvisioningHook later = named("later", 2, ran);

        TenantProvisioning provisioning = new TenantProvisioning(registry, null, List.of(failing, later));
        assertThatThrownBy(() -> provisioning.onboard("acme"))
                .isInstanceOf(TenantProvisioningException.class);

        assertThat(ran).as("work after a failure would run against a half-built tenant").isEmpty();
    }

    @Test
    @DisplayName("onboarding an already active tenant does nothing at all")
    void onboardingIsIdempotent() {
        TenantProvisioning provisioning = new TenantProvisioning(registry, null, List.of());
        provisioning.onboard("acme");
        int savesAfterFirst = registry.statusHistory.size();

        provisioning.onboard("acme");

        assertThat(registry.statusHistory)
                .as("a retried webhook must not re-run provisioning")
                .hasSize(savesAfterFirst);
    }

    @Test
    @DisplayName("a tenant left PROVISIONING by a failure is resumed on the next attempt")
    void failedProvisioningIsResumable() {
        RecordingHook flaky = new RecordingHook(0, true);
        assertThatThrownBy(() -> new TenantProvisioning(registry, null, List.of(flaky)).onboard("acme"))
                .isInstanceOf(TenantProvisioningException.class);

        // Second attempt, with a hook that works.
        RecordingHook working = new RecordingHook(0, false);
        new TenantProvisioning(registry, null, List.of(working)).onboard("acme");

        assertThat(registry.find("acme").get().status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(working.boundTenants).containsExactly("acme");
    }

    @Test
    @DisplayName("the registration's own fields survive onboarding")
    void registrationDetailsArePreserved() {
        TenantRegistration desired = new TenantRegistration(
                "globex", TenantStatus.ACTIVE, "eu-west-1", "enterprise", "shard-a",
                Map.of("plan", "enterprise"));

        new TenantProvisioning(registry, null, List.of()).onboard(desired);

        TenantRegistration saved = registry.find("globex").orElseThrow();
        assertThat(saved.region()).isEqualTo("eu-west-1");
        assertThat(saved.datasourceRef()).isEqualTo("shard-a");
        assertThat(saved.metadata()).containsEntry("plan", "enterprise");
        assertThat(saved.status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    @DisplayName("the context is left as it was found")
    void contextIsNotLeaked() {
        TenantContext.clear();
        new TenantProvisioning(registry, null, List.of(new RecordingHook(0, false))).onboard("acme");

        assertThat(TenantContext.current())
                .as("a tenant left bound would be inherited by whatever runs next on this thread")
                .isEmpty();
    }

    private static TenantProvisioningHook named(String name, int order, List<String> ran) {
        return new TenantProvisioningHook() {
            @Override
            public void onTenantCreated(String tenantId) {
                ran.add(name);
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }
}
