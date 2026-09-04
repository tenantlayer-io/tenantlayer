package io.tenantlayer.kafkasupport;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.kafka.TenantKafka;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * A batch can span tenants, so the container cannot bind one for the whole batch. Each
 * record is scoped individually with {@link TenantKafka#runAsRecordTenant}.
 */
public class BatchRecordingListener {

    private final List<String> observed = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    @KafkaListener(topics = "batch-orders", groupId = "tenantlayer-batch-test",
            containerFactory = "batchFactory")
    public void handle(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            TenantKafka.runAsRecordTenant(record, () ->
                    observed.add(record.value() + "=" + TenantContext.current()
                            .map(TenantScope::subject).orElse("none")));
            latch.countDown();
        }
    }

    public void expect(int records) {
        latch = new CountDownLatch(records);
    }

    public CountDownLatch latch() {
        return latch;
    }

    public List<String> observed() {
        return observed;
    }

    public void reset() {
        observed.clear();
    }
}
