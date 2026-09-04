package io.tenantlayer.kafka;

import io.tenantlayer.core.NoTenantException;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.concurrent.Callable;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Feature 18 — the helper a batch listener uses to scope one record at a time.
 *
 * A batch has no single tenant, so the per-record scoping the container cannot do for you
 * is done here, in one line, rather than by hand with a try/finally each time.
 */
public final class TenantKafka {

    private TenantKafka() {
    }

    /** @return the tenant the record was produced under, or {@code null}. */
    public static String tenantOf(ConsumerRecord<?, ?> record) {
        return TenantHeaders.read(record.headers());
    }

    public static void runAsRecordTenant(ConsumerRecord<?, ?> record, Runnable body) {
        callAsRecordTenant(record, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Runs the body scoped to the record's tenant. A record with no tenant header is
     * refused rather than processed with whatever was already bound.
     */
    public static <T> T callAsRecordTenant(ConsumerRecord<?, ?> record, Callable<T> body) {
        String tenant = tenantOf(record);
        if (tenant == null) {
            throw new NoTenantException("record at " + record.topic() + "-" + record.partition()
                    + "@" + record.offset() + " carries no " + TenantHeaders.TENANT_HEADER
                    + " header");
        }
        return TenantContext.callWithTenant(TenantScope.of(tenant), body);
    }
}
