package io.tenantlayer.core;

/**
 * The shipped {@link TenantContextStorage}: a plain {@link ThreadLocal}.
 *
 * <p>Not an {@code InheritableThreadLocal}, and that is deliberate. Inheritance sounds
 * like exactly what a propagation library wants, but it copies the value at thread
 * <em>creation</em>, which for a pooled executor is whenever the pool happened to grow —
 * so a worker created while serving acme keeps acme as its inherited default forever, and
 * every later task that fails to set a tenant silently runs as acme instead of failing
 * closed. Propagation is done explicitly by the decorators instead, which capture at
 * submit time and restore afterwards.
 *
 * <p>Virtual threads (feature 12) need nothing special here: each carries its own
 * thread-local map, and because they are never pooled there is no reuse to leak across.
 */
public class ThreadLocalTenantContextStorage implements TenantContextStorage {

    private final ThreadLocal<TenantScope> current = new ThreadLocal<>();

    @Override
    public TenantScope get() {
        return current.get();
    }

    @Override
    public void set(TenantScope scope) {
        current.set(scope);
    }

    @Override
    public void clear() {
        current.remove();
    }
}
