package io.tenantlayer.kafka;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Feature 18 — restores the producing tenant before the listener runs, and takes it away
 * afterwards.
 *
 * <h2>Why the teardown is the hard half</h2>
 *
 * Listener containers run on long-lived threads that process record after record. Binding
 * the tenant is easy; the failure mode is a record that carries no tenant being handled on
 * a thread still holding the previous record's, which is a cross-tenant write rather than
 * a missing read. {@link #afterRecord} therefore always clears, and it is invoked whether
 * the listener returned, threw, or was routed to an error handler — so retries and
 * recoverers unwind correctly too.
 */
public class TenantRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        String tenant = TenantHeaders.read(record.headers());
        if (tenant == null) {
            // Fail closed: no tenant on the record means no tenant for the listener, never
            // whatever the previous record on this thread happened to leave behind.
            TenantContext.clear();
        } else {
            TenantContext.enter(TenantScope.of(tenant));
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record,
                            Consumer<Object, Object> consumer) {
        TenantContext.clear();
    }
}
