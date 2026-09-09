package io.tenantlayer.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature 82 — decides which tenants are allowed to appear as a metric tag.
 *
 * <h2>The cap is the feature</h2>
 *
 * A tenant tag is the obvious thing to add and the easy way to take down a monitoring
 * system. Every distinct tag value multiplies the number of time series stored, so an
 * unbounded tenant tag on a busy endpoint turns ten thousand tenants into ten thousand times
 * as many series — and the first anyone hears of it is a Prometheus that will not start, or
 * an invoice.
 *
 * <p>So a fixed number of tenants get their own series and the rest share one. Which
 * tenants those are is simply whoever arrived first, deliberately: any cleverer policy —
 * busiest, most recent, largest — needs state that is itself unbounded, and would make the
 * thing being capped the thing doing the capping.
 *
 * <h2>Reaching the cap is not silent</h2>
 *
 * It is logged once, at WARN, naming the cap and the property that changes it. Silently
 * folding tenants into an "other" bucket would look identical to those tenants sending no
 * traffic, which is the kind of monitoring gap that is only ever found during an incident.
 */
public class TenantTagLimiter {

    private static final Logger log = LoggerFactory.getLogger(TenantTagLimiter.class);

    /** Room for a healthy SaaS to see every tenant, low enough to notice before it hurts. */
    public static final int DEFAULT_MAX_TENANTS = 100;

    /** What tenants beyond the cap are reported as. */
    public static final String DEFAULT_OVERFLOW = "__other__";

    /** What a request with no tenant is reported as — distinct from being over the cap. */
    public static final String NONE = "__none__";

    private final int maxTenants;
    private final String overflow;
    private final Set<String> admitted = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean warned = new AtomicBoolean();

    public TenantTagLimiter(int maxTenants, String overflow) {
        this.maxTenants = maxTenants > 0 ? maxTenants : DEFAULT_MAX_TENANTS;
        this.overflow = overflow == null || overflow.isBlank() ? DEFAULT_OVERFLOW : overflow;
    }

    /**
     * @param tenantId the acting tenant, or null when none is bound
     * @return the value to tag with — never null, so a meter always has the tag
     */
    public String tagFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return NONE;
        }
        if (admitted.contains(tenantId)) {
            return tenantId;
        }
        /* size() then add() is not atomic, so under contention this can admit a handful
           more than the cap. That is the right trade: the alternative is a lock on the hot
           path of every request, to enforce a limit whose exact value is arbitrary. */
        if (admitted.size() < maxTenants) {
            admitted.add(tenantId);
            return tenantId;
        }
        if (warned.compareAndSet(false, true)) {
            log.warn("metric tenant tag cap of {} reached; further tenants are reported as '{}'. "
                            + "Raise tenantlayer.metrics.max-tenants, or leave it — the cap is what "
                            + "keeps the number of time series bounded.",
                    maxTenants, overflow);
        }
        return overflow;
    }

    /** How many tenants currently have a series of their own. */
    public int admittedCount() {
        return admitted.size();
    }

    /** Whether the cap has been reached and tenants are being folded together. */
    public boolean isSaturated() {
        return warned.get();
    }
}
