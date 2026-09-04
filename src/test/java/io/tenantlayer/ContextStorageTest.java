package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantContextStorage;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.core.ThreadLocalTenantContextStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Feature 9 — the context is stored behind a swappable SPI.
 *
 * The point of these tests is not that a ThreadLocal works. It is that {@link TenantContext}
 * genuinely routes through {@link TenantContextStorage} and keeps no private copy of its
 * own — because that is the property that lets the storage be replaced later without
 * revisiting every propagation adapter.
 */
class ContextStorageTest {

    @AfterEach
    void restoreDefaultStorage() {
        TenantContext.useStorage(new ThreadLocalTenantContextStorage());
        TenantContext.clear();
    }

    @Test
    @DisplayName("TenantContext delegates every read and write to the installed storage")
    void contextDelegatesToStorage() {
        RecordingStorage recording = new RecordingStorage();
        TenantContext.useStorage(recording);

        TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                assertThat(TenantContext.require().subject()).isEqualTo("acme"));

        // If TenantContext kept its own ThreadLocal alongside the SPI, the calls would
        // still succeed and this list would be empty. That is the mutation this catches.
        assertThat(recording.calls)
                .as("the SPI must actually be on the path, not decoration")
                .contains("set:acme");
        assertThat(recording.calls)
                .as("leaving the scope must reach the storage too")
                .contains("clear");
    }

    @Test
    @DisplayName("a substituted storage decides what the context returns")
    void storageIsAuthoritative() {
        TenantContext.useStorage(new FixedStorage(TenantScope.of("globex")));

        assertThat(TenantContext.current())
                .as("the context must report what the storage holds, not what it was told")
                .contains(TenantScope.of("globex"));
    }

    @Test
    @DisplayName("null storage is refused rather than silently disabling isolation")
    void nullStorageIsRefused() {
        assertThatThrownBy(() -> TenantContext.useStorage(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("ThreadLocal storage")
    class ThreadLocalBacking {

        @Test
        @DisplayName("one thread cannot observe another's tenant")
        void tenantsDoNotLeakBetweenThreads() throws Exception {
            TenantContext.enter(TenantScope.of("acme"));

            AtomicReference<Object> seenOnOtherThread = new AtomicReference<>("unset");
            CountDownLatch done = new CountDownLatch(1);

            Thread other = new Thread(() -> {
                seenOnOtherThread.set(TenantContext.current().orElse(null));
                done.countDown();
            });
            other.start();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(seenOnOtherThread.get())
                    .as("an unrelated thread must start with no tenant, never inherit one")
                    .isNull();
            assertThat(TenantContext.require().subject())
                    .as("and the originating thread must be unaffected")
                    .isEqualTo("acme");
        }

        @Test
        @DisplayName("clear leaves no trace for the next user of the thread")
        void clearLeavesNoTrace() {
            TenantContext.enter(TenantScope.of("acme"));
            TenantContext.clear();

            assertThat(TenantContext.current()).isEmpty();
            assertThatThrownBy(TenantContext::require)
                    .as("no tenant must fail closed, not return a default")
                    .isInstanceOf(io.tenantlayer.core.NoTenantException.class);
        }
    }

    /** Records what it was asked to do, so the test can prove the SPI is on the path. */
    private static final class RecordingStorage implements TenantContextStorage {

        private final List<String> calls = new ArrayList<>();
        private TenantScope scope;

        @Override
        public TenantScope get() {
            return scope;
        }

        @Override
        public void set(TenantScope value) {
            calls.add("set:" + value.subject());
            this.scope = value;
        }

        @Override
        public void clear() {
            calls.add("clear");
            this.scope = null;
        }
    }

    /** Always reports the same tenant, whatever it is told. */
    private record FixedStorage(TenantScope fixed) implements TenantContextStorage {

        @Override
        public TenantScope get() {
            return fixed;
        }

        @Override
        public void set(TenantScope scope) {
        }

        @Override
        public void clear() {
        }
    }
}
