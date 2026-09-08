package io.tenantlayer.metrics;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;

/**
 * Feature 82 — puts the acting tenant on every observation, subject to a cardinality cap.
 *
 * <p>An {@link ObservationFilter} rather than a {@code MeterFilter} because the tenant has
 * to be read when the observation happens, on the request thread, not when the meter is
 * first registered. A meter filter runs once per meter and would capture whichever tenant
 * happened to be bound the first time that endpoint was called.
 *
 * <p>The tag is always present — {@code __none__} when no tenant is bound — so a query
 * never has to cope with a metric that sometimes has the dimension and sometimes does not.
 */
public class TenantObservationFilter implements ObservationFilter {

    /** Low cardinality by construction: the limiter is what makes that true. */
    public static final String TAG = "tenant";

    private final TenantTagLimiter limiter;

    public TenantObservationFilter(TenantTagLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        String tenant = TenantContext.current().map(TenantScope::subject).orElse(null);
        return context.addLowCardinalityKeyValue(KeyValue.of(TAG, limiter.tagFor(tenant)));
    }
}
