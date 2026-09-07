package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantStatus;
import io.tenantlayer.strategy.ConfiguredTenantDataSourceProvider;
import io.tenantlayer.strategy.TenantDatabase;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provider's routing and bounds, without a database.
 *
 * <p>A pool is built but never connected to — Hikari does not dial out until the first
 * {@code getConnection()} — so these run in milliseconds and still exercise the real
 * construction path rather than a stand-in.
 */
class TenantDataSourceProviderTest {

    private static final String URL = "jdbc:postgresql://localhost:1/never-connected";

    private static Map<String, TenantDatabase> two() {
        return Map.of("shard-a", TenantDatabase.of(URL), "shard-b", TenantDatabase.of(URL));
    }

    @Test
    @DisplayName("a tenant with no configured database yields empty, never a default")
    void unknownTenantYieldsEmpty() {
        var provider = new ConfiguredTenantDataSourceProvider(Map.of("acme", TenantDatabase.of(URL)));
        assertThat(provider.forTenant("stranger")).isEmpty();
        assertThat(provider.openPools()).as("a miss must not open anything").isZero();
    }

    @Test
    @DisplayName("a blank or null tenant yields empty rather than throwing")
    void blankTenantYieldsEmpty() {
        var provider = new ConfiguredTenantDataSourceProvider(Map.of("acme", TenantDatabase.of(URL)));
        assertThat(provider.forTenant(null)).isEmpty();
        assertThat(provider.forTenant("  ")).isEmpty();
    }

    @Test
    @DisplayName("without a registry the tenant id is its own database reference")
    void tenantIdIsItsOwnReference() {
        var provider = new ConfiguredTenantDataSourceProvider(Map.of("acme", TenantDatabase.of(URL)));
        assertThat(provider.forTenant("acme")).isPresent();
        assertThat(provider.openPools()).isEqualTo(1);
    }

    @Test
    @DisplayName("tenants grouped onto one shard share a single pool")
    void groupedTenantsShareAPool() {
        TenantRegistry registry = registryWith(Map.of("acme", "shard-a", "globex", "shard-a"));
        var provider = new ConfiguredTenantDataSourceProvider(two(), registry, 10);

        assertThat(provider.forTenant("acme")).isPresent();
        assertThat(provider.forTenant("globex")).isPresent();
        assertThat(provider.openPools())
                .as("both map to shard-a, so there is one database and one pool")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("reaching the pool ceiling fails loudly instead of recycling a live pool")
    void poolCeilingIsEnforced() {
        TenantRegistry registry = registryWith(Map.of("acme", "shard-a", "globex", "shard-b"));
        var provider = new ConfiguredTenantDataSourceProvider(two(), registry, 1);

        assertThat(provider.forTenant("acme")).isPresent();
        assertThatThrownBy(() -> provider.forTenant("globex"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum 1")
                .hasMessageContaining("tenantlayer.databases-max-pools");
        assertThat(provider.openPools()).isEqualTo(1);
    }

    @Test
    @DisplayName("shutdown closes what it opened")
    void shutdownClosesPools() {
        var provider = new ConfiguredTenantDataSourceProvider(Map.of("acme", TenantDatabase.of(URL)));
        provider.forTenant("acme");
        assertThat(provider.openPools()).isEqualTo(1);
        provider.shutdown();
        assertThat(provider.openPools()).isZero();
    }

    @Test
    @DisplayName("a database needs a url")
    void urlIsRequired() {
        assertThatThrownBy(() -> new TenantDatabase(" ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    private static TenantRegistry registryWith(Map<String, String> tenantToRef) {
        return new TenantRegistry() {
            @Override
            public Optional<TenantRegistration> find(String tenantId) {
                String ref = tenantToRef.get(tenantId);
                return ref == null
                        ? Optional.empty()
                        : Optional.of(new TenantRegistration(
                                tenantId, TenantStatus.ACTIVE, null, null, ref, Map.of()));
            }

            @Override
            public List<TenantRegistration> findAll() {
                return List.of();
            }

            @Override
            public List<String> activeTenantIds() {
                return List.copyOf(tenantToRef.keySet());
            }

            @Override
            public void save(TenantRegistration registration) {
            }

            @Override
            public boolean delete(String tenantId) {
                return false;
            }
        };
    }
}
