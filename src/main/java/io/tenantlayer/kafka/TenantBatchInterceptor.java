package io.tenantlayer.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.listener.BatchInterceptor;

/**
 * Feature 18, batch half.
 *
 * <p>A batch can span tenants, so there is no single tenant to bind for the listener — and
 * binding the first record's would be worse than binding none, because the listener would
 * then process every record in the batch as whoever happened to come first. So the context
 * is explicitly cleared around the batch, and a batch listener is expected to scope each
 * record itself:
 *
 * <pre>{@code
 * @KafkaListener(topics = "orders", batch = "true")
 * void handle(List<ConsumerRecord<String, Order>> records) {
 *     for (var record : records) {
 *         TenantKafka.runAsRecordTenant(record, () -> service.handle(record.value()));
 *     }
 * }
 * }</pre>
 */
public class TenantBatchInterceptor implements BatchInterceptor<Object, Object> {

    @Override
    public ConsumerRecords<Object, Object> intercept(ConsumerRecords<Object, Object> records,
                                                     Consumer<Object, Object> consumer) {
        io.tenantlayer.core.TenantContext.clear();
        return records;
    }

    @Override
    public void success(ConsumerRecords<Object, Object> records,
                        Consumer<Object, Object> consumer) {
        io.tenantlayer.core.TenantContext.clear();
    }

    @Override
    public void failure(ConsumerRecords<Object, Object> records, Exception exception,
                        Consumer<Object, Object> consumer) {
        io.tenantlayer.core.TenantContext.clear();
    }
}
