package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantExecutors;
import io.tenantlayer.core.TenantScope;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature 11 — the tenant survives CompletableFuture composition and hand-rolled pools.
 *
 * The failure this guards against is not an exception. A task that loses its tenant runs
 * happily against a connection published with the empty tenant and returns nothing, which
 * surfaces as missing data rather than as an error — so every test here asserts both that
 * the right tenant arrives and, where a pool is involved, that the wrong one does not
 * linger.
 */
class ExecutorPropagationTest {

    private static final String CURRENT = "current";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String currentTenant() {
        return TenantContext.current().map(TenantScope::subject).orElse(null);
    }

    @Test
    @DisplayName("supplyAsync on a wrapped executor keeps the submitter's tenant")
    void supplyAsyncKeepsTenant() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Executor wrapped = TenantExecutors.wrap((Executor) pool);

            String seen = TenantContext.callWithTenant(TenantScope.of("acme"), () ->
                    CompletableFuture.supplyAsync(ExecutorPropagationTest::currentTenant, wrapped)
                            .get());

            assertThat(seen).isEqualTo("acme");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a captured supplier carries the tenant onto the common ForkJoinPool")
    void capturedSupplierOnCommonPool() {
        String seen = TenantContext.callWithTenant(TenantScope.of("globex"), () ->
                CompletableFuture
                        .supplyAsync(TenantExecutors.supplier(ExecutorPropagationTest::currentTenant))
                        .join());

        assertThat(seen)
                .as("supplyAsync with no executor runs on a pool Spring cannot decorate")
                .isEqualTo("globex");
    }

    @Test
    @DisplayName("an uncaptured supplier on the common pool has no tenant — the bug this prevents")
    void uncapturedSupplierLosesTenant() {
        String seen = TenantContext.callWithTenant(TenantScope.of("globex"), () ->
                CompletableFuture.supplyAsync(ExecutorPropagationTest::currentTenant).join());

        assertThat(seen)
                .as("documents why supplier() exists: without it the tenant is silently gone, "
                    + "and the query would return zero rows rather than fail")
                .isNull();
    }

    @Test
    @DisplayName("a chained thenApplyAsync stage keeps the tenant")
    void chainedStageKeepsTenant() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Executor wrapped = TenantExecutors.wrap((Executor) pool);

            String seen = TenantContext.callWithTenant(TenantScope.of("acme"), () ->
                    CompletableFuture.supplyAsync(() -> "x", wrapped)
                            .thenApplyAsync(ignored -> currentTenant(), wrapped)
                            .get());

            assertThat(seen).isEqualTo("acme");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a wrapped ExecutorService propagates through submit and invokeAll")
    void executorServicePropagates() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ExecutorService wrapped = TenantExecutors.wrap(pool);

            TenantContext.enter(TenantScope.of("acme"));

            Future<String> submitted = wrapped.submit(ExecutorPropagationTest::currentTenant);
            assertThat(submitted.get()).isEqualTo("acme");

            List<Callable<String>> tasks = List.of(
                    ExecutorPropagationTest::currentTenant,
                    ExecutorPropagationTest::currentTenant);
            List<Future<String>> results = wrapped.invokeAll(tasks);

            for (Future<String> result : results) {
                assertThat(result.get())
                        .as("invokeAll must decorate every task, not just the first")
                        .isEqualTo("acme");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a pooled worker does not inherit the previous task's tenant")
    void pooledWorkerDoesNotInherit() throws Exception {
        // One thread, so the second task is guaranteed to run on the thread the first used.
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            ExecutorService wrapped = TenantExecutors.wrap(pool);

            String asAcme = TenantContext.callWithTenant(TenantScope.of("acme"), () ->
                    wrapped.submit(ExecutorPropagationTest::currentTenant).get());
            assertThat(asAcme)
                    .as("otherwise the assertion below is vacuous")
                    .isEqualTo("acme");

            TenantContext.clear();
            String withNoTenant = wrapped.submit(ExecutorPropagationTest::currentTenant).get();

            assertThat(withNoTenant)
                    .as("the worker kept acme from the previous task")
                    .isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a captured task restores whatever the worker was carrying before")
    void captureRestoresPreviousScope() {
        TenantContext.enter(TenantScope.of("outer"));

        Runnable inner = TenantContext.callWithTenant(TenantScope.of("inner"),
                () -> TenantExecutors.runnable(() -> assertThat(currentTenant()).isEqualTo("inner")));

        inner.run();

        assertThat(currentTenant())
                .as("running a captured task must leave the calling thread as it found it")
                .isEqualTo("outer");
    }
}
