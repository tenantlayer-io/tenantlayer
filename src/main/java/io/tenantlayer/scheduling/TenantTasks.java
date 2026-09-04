package io.tenantlayer.scheduling;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.registry.TenantRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature 14 — running work for tenants when no request is there to say which one.
 *
 * <h2>Why scheduled jobs are the classic leak</h2>
 *
 * A {@code @Scheduled} method runs on a scheduler thread that no filter ever touched, so
 * there is no tenant in context. Every query it makes is published with the empty tenant
 * and returns nothing. The job "succeeds" every night, does no work, and nobody notices
 * until a customer asks why their report is blank.
 *
 * <pre>{@code
 * @Scheduled(cron = "0 0 3 * * *")
 * void rebuildReports() {
 *     tenantTasks.forEachTenant(tenant -> reportService.rebuild());
 * }
 * }</pre>
 *
 * <h2>One tenant's failure does not cancel the rest</h2>
 *
 * A nightly job that aborts on the first bad tenant leaves every tenant after it in the
 * list unprocessed, and which ones those are depends on alphabetical order. So every
 * tenant is attempted, failures are collected, and a {@link TenantIterationException}
 * naming them is thrown at the end.
 */
public class TenantTasks {

    private static final Logger log = LoggerFactory.getLogger(TenantTasks.class);

    private final TenantRegistry registry;

    public TenantTasks(TenantRegistry registry) {
        this.registry = registry;
    }

    /** Runs the body once per active tenant, with that tenant bound to the context. */
    public void forEachTenant(Consumer<String> body) {
        mapEachTenant(tenant -> {
            body.accept(tenant);
            return null;
        });
    }

    /**
     * As {@link #forEachTenant}, returning each tenant's result in registry order.
     * Tenants that threw are absent from the map and present in the exception.
     */
    public <T> Map<String, T> mapEachTenant(Function<String, T> body) {
        List<String> tenants = registry.activeTenantIds();
        Map<String, T> results = new LinkedHashMap<>();
        Map<String, Throwable> failures = new LinkedHashMap<>();

        for (String tenant : tenants) {
            try {
                results.put(tenant, runAs(tenant, () -> body.apply(tenant)));
            } catch (Exception e) {
                log.warn("tenant task failed for '{}'", tenant, e);
                failures.put(tenant, e);
            }
        }

        if (!failures.isEmpty()) {
            throw new TenantIterationException(failures, tenants.size());
        }
        return results;
    }

    /**
     * Runs a body as one fixed tenant. The scope is entered and unwound around the call,
     * so a scheduler thread is left exactly as it was found — schedulers pool their
     * threads, and a job that leaves a tenant behind hands it to the next job on that
     * thread.
     */
    public <T> T runAs(String tenantId, java.util.concurrent.Callable<T> body) {
        return TenantContext.callWithTenant(TenantScope.of(tenantId), body);
    }

    public void runAs(String tenantId, Runnable body) {
        TenantContext.runWithTenant(TenantScope.of(tenantId), body);
    }

    public TenantRegistry registry() {
        return registry;
    }
}
