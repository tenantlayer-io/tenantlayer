package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.actuate.TenantsEndpoint;
import io.tenantlayer.registry.TenantProvisioning;
import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Feature 51 — the registry over HTTP, and what it refuses to do. */
class TenantsEndpointTest {

    static class InMemoryRegistry implements TenantRegistry {
        final Map<String, TenantRegistration> rows = new LinkedHashMap<>();

        @Override
        public Optional<TenantRegistration> find(String tenantId) {
            return Optional.ofNullable(rows.get(tenantId));
        }

        @Override
        public List<TenantRegistration> findAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<String> activeTenantIds() {
            return rows.values().stream().filter(TenantRegistration::isActive)
                    .map(TenantRegistration::tenantId).toList();
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

    private InMemoryRegistry registry;
    private TenantsEndpoint endpoint;
    private List<String> hookRan;

    @BeforeEach
    void setUp() {
        registry = new InMemoryRegistry();
        hookRan = new ArrayList<>();
        // A hook makes provisioning observable. Without one, onboard() and a bare
        // registry.save() produce identical results and the test cannot tell them apart —
        // which a mutation test demonstrated by swapping them and staying green.
        io.tenantlayer.registry.TenantProvisioningHook hook = tenantId -> hookRan.add(tenantId);
        endpoint = new TenantsEndpoint(registry, new TenantProvisioning(registry, null, List.of(hook)));
    }

    @Test
    @DisplayName("creating a tenant runs provisioning, not a bare registry insert")
    void onboardGoesThroughProvisioning() {
        Map<String, Object> created = endpoint.onboard("acme", "eu-west-1", "enterprise", "shard-a");

        assertThat(created).containsEntry("tenantId", "acme")
                .containsEntry("status", "ACTIVE")
                .containsEntry("servable", true)
                .containsEntry("region", "eu-west-1")
                .containsEntry("datasourceRef", "shard-a");
        assertThat(registry.find("acme")).isPresent();
        assertThat(hookRan)
                .as("a bare registry insert would leave a tenant that exists and does not work")
                .containsExactly("acme");
    }

    @Test
    @DisplayName("creating the same tenant twice is safe")
    void onboardIsIdempotent() {
        endpoint.onboard("acme", null, null, null);
        Map<String, Object> second = endpoint.onboard("acme", null, null, null);

        assertThat(second).containsEntry("status", "ACTIVE");
        assertThat(registry.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a blank tenant id is refused, by the registration's own validation")
    void blankTenantIdIsRefused() {
        assertThatThrownBy(() -> endpoint.onboard("  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThat(registry.findAll()).isEmpty();
        assertThat(hookRan).as("nothing should have been provisioned").isEmpty();
    }

    @Test
    @DisplayName("listing reports every tenant and whether each is servable")
    void listingReportsStatus() {
        endpoint.onboard("acme", null, null, null);
        endpoint.onboard("globex", null, null, null);
        endpoint.status("globex", "SUSPENDED");

        Map<String, Object> body = endpoint.tenants();

        assertThat(body).containsEntry("count", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tenants = (List<Map<String, Object>>) body.get("tenants");
        assertThat(tenants).extracting(t -> t.get("servable")).containsExactly(true, false);
    }

    @Test
    @DisplayName("an unknown tenant reads as absent rather than as an error")
    void unknownTenantReadsAsNull() {
        assertThat(endpoint.tenant("nobody")).isNull();
    }

    @Test
    @DisplayName("suspending and reactivating change what is servable")
    void statusCanBeChangedBothWays() {
        endpoint.onboard("acme", null, null, null);

        assertThat(endpoint.status("acme", "SUSPENDED")).containsEntry("servable", false);
        assertThat(registry.activeTenantIds()).isEmpty();

        assertThat(endpoint.status("acme", "ACTIVE")).containsEntry("servable", true);
        assertThat(registry.activeTenantIds()).containsExactly("acme");
    }

    @Test
    @DisplayName("PROVISIONING cannot be set by hand, because it would claim work that never ran")
    void provisioningCannotBeSetManually() {
        endpoint.onboard("acme", null, null, null);

        assertThatThrownBy(() -> endpoint.status("acme", "PROVISIONING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("onboarding");
        assertThat(registry.find("acme").orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    @DisplayName("changing the status of a tenant that does not exist is refused")
    void statusOfUnknownTenantIsRefused() {
        assertThatThrownBy(() -> endpoint.status("nobody", "SUSPENDED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no such tenant");
    }

    @Test
    @DisplayName("delete removes the row and says plainly that the data remains")
    void deleteRemovesTheRowOnly() {
        endpoint.onboard("acme", null, null, null);

        Map<String, Object> result = endpoint.delete("acme");

        assertThat(result).containsEntry("removed", true);
        assertThat(String.valueOf(result.get("note")))
                .as("an operator must not believe this erased the tenant's data")
                .contains("data is not");
        assertThat(registry.find("acme")).isEmpty();
    }

    @Test
    @DisplayName("the endpoint is off unless it is both enabled and exposed")
    void endpointIsDisabledByDefault() throws Exception {
        org.springframework.boot.actuate.endpoint.annotation.Endpoint annotation =
                TenantsEndpoint.class.getAnnotation(
                        org.springframework.boot.actuate.endpoint.annotation.Endpoint.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.enableByDefault())
                .as("an endpoint that can create tenants must not be on by accident")
                .isFalse();
        assertThat(annotation.id()).isEqualTo("tenants");
    }
}
