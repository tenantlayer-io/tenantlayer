package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.tenantlayer.autoconfigure.TenantMetricsAutoConfiguration;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.metrics.TenantObservationFilter;
import io.tenantlayer.metrics.TenantTagLimiter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Feature 82 — the tenant tag, and the cap that is the actual feature. */
class TenantMetricsTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Nested
    class Cap {

        @Test
        @DisplayName("tenants within the cap each get their own tag value")
        void tenantsWithinTheCapKeepTheirIdentity() {
            TenantTagLimiter limiter = new TenantTagLimiter(3, "__other__");

            assertThat(limiter.tagFor("acme")).isEqualTo("acme");
            assertThat(limiter.tagFor("globex")).isEqualTo("globex");
            assertThat(limiter.tagFor("initech")).isEqualTo("initech");
            assertThat(limiter.admittedCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("beyond the cap, further tenants share one series instead of creating more")
        void tenantsBeyondTheCapAreFoldedTogether() {
            TenantTagLimiter limiter = new TenantTagLimiter(2, "__other__");
            limiter.tagFor("acme");
            limiter.tagFor("globex");

            assertThat(limiter.tagFor("umbrella")).isEqualTo("__other__");
            assertThat(limiter.tagFor("soylent")).isEqualTo("__other__");
            assertThat(limiter.admittedCount())
                    .as("the cap must bound how many series exist, not merely rename them")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("an already-admitted tenant keeps its own series after the cap is reached")
        void admittedTenantsAreUnaffectedBySaturation() {
            TenantTagLimiter limiter = new TenantTagLimiter(1, "__other__");
            limiter.tagFor("acme");
            limiter.tagFor("globex");   // saturates

            assertThat(limiter.tagFor("acme")).isEqualTo("acme");
        }

        @Test
        @DisplayName("reaching the cap is recorded, so it is not silent")
        void saturationIsObservable() {
            TenantTagLimiter limiter = new TenantTagLimiter(1, "__other__");
            assertThat(limiter.isSaturated()).isFalse();

            limiter.tagFor("acme");
            assertThat(limiter.isSaturated()).as("the cap was not reached yet").isFalse();

            limiter.tagFor("globex");
            assertThat(limiter.isSaturated()).isTrue();
        }

        @Test
        @DisplayName("no tenant is its own value, distinct from being over the cap")
        void noTenantIsNotTheSameAsOverflow() {
            TenantTagLimiter limiter = new TenantTagLimiter(10, "__other__");

            assertThat(limiter.tagFor(null)).isEqualTo(TenantTagLimiter.NONE);
            assertThat(limiter.tagFor("  ")).isEqualTo(TenantTagLimiter.NONE);
            assertThat(limiter.tagFor(null)).isNotEqualTo("__other__");
            assertThat(limiter.admittedCount())
                    .as("absence of a tenant must not consume a slot")
                    .isZero();
        }

        @Test
        @DisplayName("the cap holds under concurrent traffic from many tenants")
        void capHoldsUnderConcurrency() throws Exception {
            int cap = 20;
            TenantTagLimiter limiter = new TenantTagLimiter(cap, "__other__");
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);

            List<Runnable> work = IntStream.range(0, 500)
                    .mapToObj(i -> (Runnable) () -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        limiter.tagFor("tenant-" + i);
                    })
                    .toList();
            work.forEach(pool::submit);
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

            /* size() then add() is not atomic, so a small overshoot under contention is
               accepted deliberately rather than locking the hot path. What must not happen
               is 500 tenants each getting a series. */
            assertThat(limiter.admittedCount()).isLessThan(cap + 16);
        }
    }

    @Nested
    class Tagging {

        @Test
        @DisplayName("the bound tenant is attached to the observation")
        void theBoundTenantIsTagged() {
            TenantObservationFilter filter = new TenantObservationFilter(new TenantTagLimiter(10, "__other__"));

            String tag = TenantContext.callWithTenant(TenantScope.of("acme"), () -> {
                Observation.Context context = new Observation.Context();
                filter.map(context);
                return context.getLowCardinalityKeyValue(TenantObservationFilter.TAG).getValue();
            });

            assertThat(tag).isEqualTo("acme");
        }

        @Test
        @DisplayName("the tag is present even with no tenant, so the dimension never disappears")
        void theTagIsAlwaysPresent() {
            TenantObservationFilter filter = new TenantObservationFilter(new TenantTagLimiter(10, "__other__"));
            TenantContext.clear();

            Observation.Context context = new Observation.Context();
            filter.map(context);

            assertThat(context.getLowCardinalityKeyValue(TenantObservationFilter.TAG))
                    .as("a metric that sometimes has the dimension and sometimes does not is "
                            + "harder to query than one that always has it")
                    .isNotNull();
            assertThat(context.getLowCardinalityKeyValue(TenantObservationFilter.TAG).getValue())
                    .isEqualTo(TenantTagLimiter.NONE);
        }
    }

    @Nested
    class Wiring {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantMetricsAutoConfiguration.class));

        @Test
        @DisplayName("the filter is registered by default")
        void registeredByDefault() {
            runner.run(context -> assertThat(context).hasSingleBean(TenantObservationFilter.class));
        }

        @Test
        @DisplayName("it can be turned off")
        void canBeDisabled() {
            runner.withPropertyValues("tenantlayer.metrics.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(TenantObservationFilter.class));
        }

        @Test
        @DisplayName("the cap is configurable")
        void capIsConfigurable() {
            runner.withPropertyValues("tenantlayer.metrics.max-tenants=2")
                    .run(context -> {
                        TenantTagLimiter limiter = context.getBean(TenantTagLimiter.class);
                        limiter.tagFor("a");
                        limiter.tagFor("b");
                        assertThat(limiter.tagFor("c")).isEqualTo(TenantTagLimiter.DEFAULT_OVERFLOW);
                    });
        }

        @Test
        @DisplayName("without Micrometer on the classpath the context still starts")
        void degradesWithoutMicrometer() {
            runner.withClassLoader(new FilteredClassLoader("io.micrometer.observation"))
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }
}
