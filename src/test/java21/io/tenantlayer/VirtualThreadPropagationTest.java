package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantExecutors;
import io.tenantlayer.core.TenantScope;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Feature 12 — isolation under Project Loom.
 *
 * <p>Compiled and run only on JDK 21+, via the {@code jdk21} profile. The library itself
 * still targets Java 17; this source directory exists because the APIs under test do not
 * exist below 21, and a test that cannot be compiled is not a test.
 *
 * <p>{@link #bootsVirtualThreadExecutorKeepsTheTenant()} is the one that matters. Setting
 * {@code spring.threads.virtual.enabled=true} changes which executor implementation Boot
 * builds, and a task decorator registered only for the pooled one vanishes along with it.
 */
class VirtualThreadPropagationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskExecutionAutoConfiguration.class, TenantLayerAutoConfiguration.class))
            .withPropertyValues("spring.threads.virtual.enabled=true");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Boot's virtual-thread executor still carries the tenant")
    void bootsVirtualThreadExecutorKeepsTheTenant() {
        runner.run(context -> {
            AsyncTaskExecutor executor = context.getBean(AsyncTaskExecutor.class);

            AtomicReference<String> seen = new AtomicReference<>();
            AtomicBoolean virtual = new AtomicBoolean();

            TenantContext.enter(TenantScope.of("acme"));
            executor.submit(() -> {
                virtual.set(Thread.currentThread().isVirtual());
                seen.set(TenantContext.current().map(TenantScope::subject).orElse(null));
            }).get();

            assertThat(virtual.get())
                    .as("if this is false the test is not exercising virtual threads at all")
                    .isTrue();
            assertThat(seen.get())
                    .as("enabling virtual threads must not quietly drop the task decorator")
                    .isEqualTo("acme");
        });
    }

    @Test
    @DisplayName("the virtual-thread executor does not leak a tenant into an untenanted task")
    void bootsVirtualThreadExecutorDoesNotLeak() {
        runner.run(context -> {
            AsyncTaskExecutor executor = context.getBean(AsyncTaskExecutor.class);

            TenantContext.enter(TenantScope.of("acme"));
            executor.submit(() -> TenantContext.current()).get();

            TenantContext.clear();
            AtomicReference<String> seen = new AtomicReference<>("unset");
            executor.submit(() ->
                    seen.set(TenantContext.current().map(TenantScope::subject).orElse(null))).get();

            assertThat(seen.get())
                    .as("a task submitted with no tenant must run with no tenant")
                    .isNull();
        });
    }

    @Test
    @DisplayName("a virtual thread per task keeps the submitter's tenant")
    void virtualThreadPerTaskExecutor() throws Exception {
        try (ExecutorService raw = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutorService wrapped = TenantExecutors.wrap(raw);

            String seen = TenantContext.callWithTenant(TenantScope.of("globex"), () ->
                    wrapped.submit(() ->
                            TenantContext.current().map(TenantScope::subject).orElse(null)).get());

            assertThat(seen).isEqualTo("globex");
        }
    }

    @Test
    @DisplayName("virtual threads are never pooled, so nothing is left behind to inherit")
    void virtualThreadsDoNotInheritAcrossTasks() throws Exception {
        try (ExecutorService raw = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutorService wrapped = TenantExecutors.wrap(raw);

            TenantContext.callWithTenant(TenantScope.of("acme"), () ->
                    wrapped.submit(TenantContext::current).get());

            String leaked = wrapped.submit(() ->
                    TenantContext.current().map(TenantScope::subject).orElse(null)).get();

            assertThat(leaked)
                    .as("a task submitted with no tenant must run with no tenant")
                    .isNull();
        }
    }

    @Test
    @DisplayName("CompletableFuture chains on virtual threads keep the tenant")
    void completableFutureOnVirtualThreads() throws Exception {
        try (ExecutorService raw = Executors.newVirtualThreadPerTaskExecutor()) {
            Executor executor = TenantExecutors.wrap((Executor) raw);

            String seen = TenantContext.callWithTenant(TenantScope.of("acme"), () ->
                    CompletableFuture
                            .supplyAsync(() -> TenantContext.current()
                                    .map(TenantScope::subject).orElse(null), executor)
                            .join());

            assertThat(seen).isEqualTo("acme");
        }
    }
}
