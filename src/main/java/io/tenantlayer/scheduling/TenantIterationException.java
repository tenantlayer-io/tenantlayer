package io.tenantlayer.scheduling;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Some tenants failed during a {@code forEachTenant} run. Names every one of them, because
 * "the nightly job failed" is not an actionable alert and "the nightly job failed for
 * globex and initech" is.
 */
public class TenantIterationException extends RuntimeException {

    private final transient Map<String, Throwable> failures;

    public TenantIterationException(Map<String, Throwable> failures, int attempted) {
        super(failures.size() + " of " + attempted + " tenants failed: "
              + String.join(", ", failures.keySet()));
        // Not Map.copyOf: that returns a map with unspecified iteration order, so the
        // list of failed tenants an operator reads would come back shuffled on each run.
        // Registry order is stable and reproducible, which is what an alert needs.
        this.failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
        failures.values().stream().findFirst().ifPresent(this::initCause);
    }

    public Map<String, Throwable> failures() {
        return failures;
    }

    public List<String> failedTenants() {
        return List.copyOf(failures.keySet());
    }
}
