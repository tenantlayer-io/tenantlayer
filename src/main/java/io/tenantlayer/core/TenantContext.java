package io.tenantlayer.core;

import java.util.Optional;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * Holds the tenant for the current unit of work.
 *
 * Deliberately the only way to read or set it, so every propagation adapter goes through
 * one contract rather than each reaching for its own ThreadLocal. Where the value is
 * physically kept is {@link TenantContextStorage}'s business, not the callers'.
 */
public final class TenantContext {

    /** Feature 83 — MDC key the tenant is published under, for log enrichment. */
    public static final String MDC_KEY = "tenant";

    private static volatile TenantContextStorage storage = new ThreadLocalTenantContextStorage();

    private TenantContext() {
    }

    /**
     * Replaces the backing storage. Call once during start-up, before any tenant is bound;
     * swapping it under a running application abandons whatever the old storage held.
     *
     * <p>Exists so a {@code ScopedValue} implementation can be dropped in on a new enough
     * JDK, and so tests can substitute a storage that records what it was asked to do.
     */
    public static void useStorage(TenantContextStorage replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("storage must not be null");
        }
        storage = replacement;
    }

    public static TenantContextStorage storage() {
        return storage;
    }

    public static Optional<TenantScope> current() {
        return Optional.ofNullable(storage.get());
    }

    /** The resolved tenant, or fail. Callers on the enforcement path use this. */
    public static TenantScope require() {
        TenantScope scope = storage.get();
        if (scope == null) {
            throw new NoTenantException("no tenant in context");
        }
        return scope;
    }

    public static void runWithTenant(TenantScope scope, Runnable body) {
        callWithTenant(scope, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T callWithTenant(TenantScope scope, Callable<T> body) {
        TenantScope previous = storage.get();
        enter(scope);
        try {
            return body.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            exit(previous);
        }
    }

    /**
     * For callers whose begin and end are separate calls (JUnit hooks, servlet filters,
     * message listeners) and cannot wrap a lambda. Pair every enter with an exit.
     */
    public static void enter(TenantScope scope) {
        if (scope == null) {
            storage.clear();
            MDC.remove(MDC_KEY);
        } else {
            storage.set(scope);
            MDC.put(MDC_KEY, scope.subject());
        }
    }

    public static void exit(TenantScope previous) {
        enter(previous);
    }

    public static void clear() {
        storage.clear();
        MDC.remove(MDC_KEY);
    }
}
