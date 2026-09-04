package io.tenantlayer.kafka;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * Feature 17 — stamps the producing tenant onto every record.
 *
 * <p>A Kafka client {@code ProducerInterceptor} rather than a Spring one, so it applies to
 * everything that publishes through the producer factory: {@code KafkaTemplate}, Spring
 * Cloud Stream, and any code that got hold of the raw producer. Kafka instantiates this
 * reflectively with no dependency injection, which is fine because the tenant is read from
 * the static {@link TenantContext} rather than from an injected collaborator.
 *
 * <p>A record produced with no tenant bound carries no tenant header, rather than an empty
 * one — the consumer must be able to tell "no tenant was set" from "the tenant is the
 * empty string", and act on the first by failing closed.
 */
public class TenantProducerInterceptor implements ProducerInterceptor<Object, Object> {

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        TenantContext.current().map(TenantScope::subject)
                .ifPresent(tenant -> TenantHeaders.write(record.headers(), tenant));
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
