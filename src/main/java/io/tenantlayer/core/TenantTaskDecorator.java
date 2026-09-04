package io.tenantlayer.core;

import org.springframework.core.task.TaskDecorator;

/**
 * Carries the tenant across a thread boundary.
 *
 * The scope is captured on the <em>submitting</em> thread at decoration time, not read on
 * the worker thread — by the time the worker runs, the request that submitted it may
 * already have completed and cleared its context.
 *
 * The worker's previous scope is restored afterwards because pool threads are reused; a
 * worker that keeps the last task's tenant is the same leak as a connection that does.
 */
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TenantScope captured = TenantContext.current().orElse(null);
        return () -> {
            TenantScope previous = TenantContext.current().orElse(null);
            TenantContext.enter(captured);
            try {
                runnable.run();
            } finally {
                TenantContext.exit(previous);
            }
        };
    }
}
