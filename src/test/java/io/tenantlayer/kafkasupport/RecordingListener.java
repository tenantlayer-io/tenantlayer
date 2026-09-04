package io.tenantlayer.kafkasupport;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * An ordinary listener. It reads no header and takes no tenant parameter — it just asks
 * TenantContext what it is, which is the whole claim under test.
 */
public class RecordingListener {

    /** "acme", "globex", or the literal string "none" when no tenant was bound. */
    private final List<String> observed = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch latch = new CountDownLatch(1);

    @KafkaListener(topics = "orders", groupId = "tenantlayer-test")
    public void handle(String payload) {
        observed.add(TenantContext.current().map(TenantScope::subject).orElse("none"));
        latch.countDown();
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
