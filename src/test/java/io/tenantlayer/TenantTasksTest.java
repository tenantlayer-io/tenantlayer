package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.registry.JdbcTenantRegistry;
import io.tenantlayer.scheduling.TenantIterationException;
import io.tenantlayer.scheduling.TenantTasks;
import io.tenantlayer.support.DocumentRepository;
import io.tenantlayer.support.TenantLayerTestBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Feature 14 — work that runs when no request exists to say which tenant it is for.
 *
 * {@link #eachIterationSeesOnlyItsOwnTenantsRows()} is the one worth reading. A helper that
 * merely calls a lambda N times is not the feature; a helper that makes the database
 * return different rows on each of those N calls is.
 */
class TenantTasksTest extends TenantLayerTestBase {

    @Autowired
    private DocumentRepository documents;

    private TenantTasks tasks;

    @BeforeEach
    void createTasks() {
        tasks = new TenantTasks(new JdbcTenantRegistry(applicationDataSource));
        TenantContext.clear();
    }

    @Test
    @DisplayName("the body runs once per active tenant, with that tenant bound")
    void runsOncePerActiveTenant() {
        List<String> seen = new ArrayList<>();

        tasks.forEachTenant(tenant -> seen.add(
                tenant + "=" + TenantContext.require().subject()));

        assertThat(seen)
                .as("the bound tenant must match the tenant being iterated")
                .containsExactly("acme=acme", "globex=globex");
    }

    @Test
    @DisplayName("each iteration sees only its own tenant's rows")
    void eachIterationSeesOnlyItsOwnTenantsRows() {
        Map<String, Long> counts = new LinkedHashMap<>(
                tasks.mapEachTenant(tenant -> documents.count()));

        assertThat(counts)
                .as("acme has 2 seeded documents and globex 3; identical counts would mean "
                    + "the iteration bound no tenant and the policy filtered everything")
                .containsEntry("acme", 2L)
                .containsEntry("globex", 3L);
    }

    @Test
    @DisplayName("suspended tenants are skipped")
    void suspendedTenantsAreSkipped() {
        List<String> seen = new ArrayList<>();

        tasks.forEachTenant(seen::add);

        assertThat(seen).doesNotContain("initech");
        assertThat(tasks.registry().find("initech"))
                .as("initech must exist but be suspended, or this proves nothing")
                .isPresent();
    }

    @Test
    @DisplayName("the scheduler thread is left with no tenant afterwards")
    void leavesNoTenantBehind() {
        tasks.forEachTenant(tenant -> { });

        assertThat(TenantContext.current())
                .as("schedulers pool threads; a job that leaks its tenant poisons the next job")
                .isEmpty();
    }

    @Test
    @DisplayName("one tenant failing does not cancel the others")
    void oneFailureDoesNotCancelTheRest() {
        List<String> attempted = new ArrayList<>();

        assertThatThrownBy(() -> tasks.forEachTenant(tenant -> {
            attempted.add(tenant);
            if (tenant.equals("acme")) {
                throw new IllegalStateException("boom");
            }
        }))
                .isInstanceOf(TenantIterationException.class)
                .hasMessageContaining("acme");

        assertThat(attempted)
                .as("globex sorts after acme; aborting on the first failure would skip it")
                .containsExactly("acme", "globex");
    }

    @Test
    @DisplayName("the aggregate exception names every tenant that failed")
    void aggregateNamesEveryFailure() {
        assertThatThrownBy(() -> tasks.forEachTenant(tenant -> {
            throw new IllegalStateException("boom for " + tenant);
        }))
                .isInstanceOfSatisfying(TenantIterationException.class, e ->
                        assertThat(e.failedTenants()).containsExactly("acme", "globex"));
    }

    @Test
    @DisplayName("a failure still leaves the thread clean")
    void failureStillUnwindsContext() {
        assertThatThrownBy(() -> tasks.forEachTenant(tenant -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(TenantIterationException.class);

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("runAs binds one fixed tenant and restores what was there before")
    void runAsBindsAndRestores() {
        TenantContext.enter(TenantScope.of("outer"));

        long acmeDocuments = tasks.runAs("acme", () -> documents.count());

        assertThat(acmeDocuments).isEqualTo(2L);
        assertThat(TenantContext.require().subject())
                .as("a fixed-tenant job must not strand the caller in its tenant")
                .isEqualTo("outer");
    }
}
