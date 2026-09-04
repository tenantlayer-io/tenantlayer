package io.tenantlayer.core;

/**
 * Feature 9 — where the current tenant is physically kept.
 *
 * <h2>Why this is an interface</h2>
 *
 * Today the answer is a {@link ThreadLocal}. On a JDK where {@code ScopedValue} is a final
 * API rather than a preview one, the better answer is a {@code ScopedValue}: it is
 * immutable for the duration of a binding, inherited by structured-concurrency forks
 * automatically, and cannot be left behind on a pooled thread because there is no setter
 * to forget to unset.
 *
 * <p>The reason to define the seam now, while there is only one implementation, is that
 * every propagation adapter in the library — the servlet filter, the task decorator, the
 * executor wrapper, the Kafka listener, the scheduler — reads and writes the context.
 * If the storage mechanism changes later and those call sites each reach for their own
 * {@code ThreadLocal}, every one of them is a separate migration and a separate chance to
 * get it wrong. They all go through {@link TenantContext}, and {@code TenantContext} goes
 * through this.
 *
 * <p><strong>Status:</strong> {@link ThreadLocalTenantContextStorage} ships. A
 * {@code ScopedValue} implementation is deliberately not included, because it would force
 * {@code --enable-preview} on every consumer for as long as the library's baseline is
 * below JDK 25. See {@code docs/context-storage.md}.
 *
 * <h2>Contract</h2>
 *
 * An implementation must be safe for concurrent use from many threads, and must not let
 * one thread observe another's tenant. Returning {@code null} from {@link #get()} means
 * "no tenant", which callers on the enforcement path treat as fail-closed rather than as
 * permission to see everything.
 */
public interface TenantContextStorage {

    /** The tenant bound to the caller's unit of work, or {@code null} when there is none. */
    TenantScope get();

    /** Binds a tenant to the caller's unit of work. */
    void set(TenantScope scope);

    /** Unbinds any tenant. Must leave no trace on a thread that will be reused. */
    void clear();
}
