package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orders.support.Api;
import com.acme.orders.support.OrdersDatabase;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.scheduling.TenantTasks;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Features 14, 50 and 56 from the consuming side.
 *
 * <p>This is the case a request-scoped tenancy layer cannot serve: nightly work, on a
 * scheduler thread, with no request and therefore no header to read. Done wrong it is not
 * an error — every query runs with no tenant, returns nothing, and the job reports success
 * having done nothing at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduledJobIsolationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        OrdersDatabase.bindDataSource(registry);
    }

    @BeforeAll
    static void prepare() {
        OrdersDatabase.reset();
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private TenantRegistry registry;

    @Autowired
    private OrderRepository orders;

    private TenantTasks tasks;

    @BeforeEach
    void setUp() {
        OrdersDatabase.truncate();
        tasks = new TenantTasks(registry);

        Api api = new Api(http);
        api.placeAs("acme", "acme corp", "laptop", 120_000);
        api.placeAs("globex", "globex ltd", "monitor", 40_000);
        api.placeAs("globex", "globex ltd", "keyboard", 8_000);
        TenantContext.clear();
    }

    @Test
    @DisplayName("the registry is autoconfigured from the application's own DataSource")
    void registryIsAutoconfigured() {
        assertThat(registry).isNotNull();
        assertThat(registry.activeTenantIds())
                .as("suspended tenants are excluded")
                .containsExactly("acme", "globex");
        assertThat(registry.find("acme").orElseThrow().region()).isEqualTo("eu-west-1");
    }

    @Test
    @DisplayName("a nightly job sees each tenant's own orders and only those")
    void nightlyJobIsScopedPerTenant() {
        Map<String, Long> counts = tasks.mapEachTenant(tenant -> orders.count());

        assertThat(counts)
                .as("identical counts would mean the job ran with no tenant bound and the "
                    + "policy filtered everything to nothing")
                .containsEntry("acme", 1L)
                .containsEntry("globex", 2L);
    }

    @Test
    @DisplayName("the job never sees a suspended tenant's data")
    void suspendedTenantIsSkipped() {
        Map<String, Long> counts = tasks.mapEachTenant(tenant -> orders.count());

        assertThat(counts).doesNotContainKey("initech");
    }

    @Test
    @DisplayName("the scheduler thread is left clean for the next job")
    void schedulerThreadIsLeftClean() {
        tasks.forEachTenant(tenant -> orders.count());

        assertThat(TenantContext.current())
                .as("a job that leaks its last tenant hands it to whatever runs next")
                .isEmpty();
    }

    @Test
    @DisplayName("without a tenant bound, the same query returns nothing at all")
    void withoutATenantTheJobSeesNothing() {
        assertThat(orders.count())
                .as("this is what a hand-rolled scheduled job silently does: no error, no rows")
                .isZero();
    }
}
