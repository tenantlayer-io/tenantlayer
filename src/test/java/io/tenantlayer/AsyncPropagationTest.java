package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.support.AsyncObservation;
import io.tenantlayer.support.AsyncProbe;
import io.tenantlayer.support.TenantLayerTestBase;
import io.tenantlayer.test.WithTenant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PROOF 3 — the tenant survives a thread boundary, and does not outstay its welcome.
 *
 * A policy cannot help if the tenant never reached the thread that opens the connection.
 * @Async is where hand-rolled implementations lose it, and the failure is silent: the
 * worker sees nothing, the query returns empty, and nobody gets an error.
 */
class AsyncPropagationTest extends TenantLayerTestBase {

    @Autowired
    private AsyncProbe probe;

    @Test
    @WithTenant("acme")
    @DisplayName("a query issued from an @Async worker is scoped to the caller's tenant")
    void tenantSurvivesAsyncBoundary() throws Exception {
        String callerThread = Thread.currentThread().getName();

        AsyncObservation observed = probe.observe().get(5, TimeUnit.SECONDS);

        assertThat(observed.threadName())
                .as("work never left the calling thread, so this proves nothing about propagation")
                .isNotEqualTo(callerThread)
                .startsWith("tenant-async-");
        assertThat(observed.tenant())
                .as("tenant was lost crossing into the @Async executor")
                .isEqualTo("acme");
        assertThat(observed.visibleRows())
                .as("the worker held the tenant but its query was not scoped by it")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a reused worker thread does not inherit the previous task's tenant")
    void workerThreadDoesNotLeakTenantToTheNextTask() throws Exception {
        AsyncObservation asAcme = TenantContext.callWithTenant(
                TenantScope.of("acme"), () -> probe.observe().get(5, TimeUnit.SECONDS));

        assertThat(asAcme.tenant()).isEqualTo("acme");

        // Same executor, single worker, now submitted with nothing in context.
        AsyncObservation withNoTenant = probe.observe().get(5, TimeUnit.SECONDS);

        assertThat(withNoTenant.threadName())
                .as("a different worker ran this, so thread reuse was never exercised")
                .isEqualTo(asAcme.threadName());
        assertThat(withNoTenant.tenant())
                .as("the worker thread kept the previous task's tenant")
                .isNull();
        assertThat(withNoTenant.visibleRows())
                .as("a task with no tenant read rows anyway")
                .isZero();
    }
}
