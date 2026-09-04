package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.registry.JdbcTenantRegistry;
import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantStatus;
import io.tenantlayer.support.TenantLayerTestBase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Features 50 and 56 — the registry.
 *
 * The load-bearing test here is {@link #registryIsReadableWithNoTenantBound()}. Everything
 * else in this library is designed so that data is invisible without a tenant; the
 * registry has to be the exception, because it is what the code consults in order to work
 * out which tenant it is serving. Getting that backwards produces a system that cannot
 * start.
 */
class RegistryTest extends TenantLayerTestBase {

    private TenantRegistry registry;

    @BeforeEach
    void createRegistry() {
        registry = new JdbcTenantRegistry(applicationDataSource);
        TenantContext.clear();
    }

    @Test
    @DisplayName("the registry is readable with no tenant bound to the context")
    void registryIsReadableWithNoTenantBound() {
        assertThat(TenantContext.current())
                .as("this test is only meaningful with no tenant bound")
                .isEmpty();

        assertThat(registry.activeTenantIds())
                .as("resolution happens before a tenant is known; an empty registry here "
                    + "would mean the library can never identify anyone")
                .containsExactly("acme", "globex");
    }

    @Test
    @DisplayName("region and group survive a round trip (feature 56)")
    void regionAndGroupRoundTrip() {
        TenantRegistration acme = registry.find("acme").orElseThrow();

        assertThat(acme.region()).isEqualTo("eu-west-1");
        assertThat(acme.group()).isEqualTo("direct");
        assertThat(acme.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(acme.metadata()).containsEntry("plan", "pro");
    }

    @Test
    @DisplayName("save writes every column, including the ones nothing reads yet")
    void saveWritesEveryColumn() {
        registry.save(new TenantRegistration("umbrella", TenantStatus.ACTIVE, "ap-south-1",
                "acme-partners", "ds-2", Map.of("plan", "enterprise", "csm", "priya")));

        TenantRegistration saved = registry.find("umbrella").orElseThrow();

        assertThat(saved.region()).isEqualTo("ap-south-1");
        assertThat(saved.group()).isEqualTo("acme-partners");
        assertThat(saved.datasourceRef()).isEqualTo("ds-2");
        assertThat(saved.metadata())
                .containsEntry("plan", "enterprise")
                .containsEntry("csm", "priya");
    }

    @Test
    @DisplayName("save is an upsert, not a duplicate-key failure")
    void saveUpserts() {
        registry.save(new TenantRegistration("acme", TenantStatus.ACTIVE, "us-west-2",
                "direct", null, Map.of("plan", "enterprise")));

        assertThat(registry.find("acme").orElseThrow().region()).isEqualTo("us-west-2");
        assertThat(registry.findAll()).extracting(TenantRegistration::tenantId)
                .as("upsert must update the row, not add a second one")
                .containsOnlyOnce("acme");
    }

    @Test
    @DisplayName("activeTenantIds excludes suspended tenants")
    void activeExcludesSuspended() {
        assertThat(registry.find("initech").orElseThrow().status())
                .as("initech must really be suspended, or the exclusion below proves nothing")
                .isEqualTo(TenantStatus.SUSPENDED);

        assertThat(registry.activeTenantIds()).doesNotContain("initech");
        assertThat(registry.findAll()).extracting(TenantRegistration::tenantId)
                .as("findAll is unfiltered; only activeTenantIds filters")
                .contains("initech");
    }

    @Test
    @DisplayName("an unknown tenant is absent, not an error")
    void unknownTenantIsEmpty() {
        assertThat(registry.find("does-not-exist")).isEmpty();
        assertThat(registry.exists("does-not-exist")).isFalse();
        assertThat(registry.exists("acme")).isTrue();
    }

    @Test
    @DisplayName("delete reports whether it removed anything")
    void deleteReportsOutcome() {
        registry.save(TenantRegistration.of("temporary"));

        assertThat(registry.delete("temporary")).isTrue();
        assertThat(registry.delete("temporary")).isFalse();
        assertThat(registry.find("temporary")).isEmpty();
    }

    @Test
    @DisplayName("the table name is validated, because it cannot be a bind parameter")
    void tableNameIsValidated() {
        assertThatThrownBy(() ->
                new JdbcTenantRegistry(applicationDataSource, "tenants; drop table documents"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain SQL identifier");

        assertThatThrownBy(() -> new JdbcTenantRegistry(applicationDataSource, "1_bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("registry access is unaffected by whichever tenant happens to be acting")
    void registryIgnoresTheActingTenant() {
        var asAcme = TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> registry.activeTenantIds());
        var asGlobex = TenantContext.callWithTenant(TenantScope.of("globex"),
                () -> registry.activeTenantIds());

        assertThat(asAcme)
                .as("the registry is shared infrastructure, not tenant-scoped data")
                .isEqualTo(asGlobex)
                .containsExactly("acme", "globex");
    }
}
