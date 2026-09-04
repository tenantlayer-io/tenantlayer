package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.kafkasupport.KafkaTestApplication;
import io.tenantlayer.kafkasupport.BatchRecordingListener;
import io.tenantlayer.kafkasupport.RecordingListener;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Features 17, 18 and 92 — produced under one tenant, consumed as that same tenant, with
 * nothing in the application saying so.
 *
 * {@link #aRecordWithoutATenantDoesNotInheritTheLastOne()} is the one to keep. Listener
 * containers process record after record on one long-lived thread, so the dangerous
 * failure is not a lost tenant but a retained one: an untenanted record handled as
 * whoever came before it. That is a cross-tenant write, and it would never show up as an
 * empty result set.
 */
@SpringBootTest(classes = KafkaTestApplication.class)
@Testcontainers
class KafkaPropagationTest {

    @Container
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private RecordingListener listener;

    @Autowired
    private BatchRecordingListener batchListener;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @BeforeEach
    void waitForAssignment() {
        registry.getListenerContainers().forEach(container ->
                ContainerTestUtils.waitForAssignment(container, 1));
        listener.reset();
        batchListener.reset();
        TenantContext.clear();
    }

    @Test
    @DisplayName("the producing tenant is restored on the consumer")
    void producingTenantIsRestoredOnConsume() throws Exception {
        listener.expect(2);

        TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                kafkaTemplate.send("orders", "order-1"));
        TenantContext.runWithTenant(TenantScope.of("globex"), () ->
                kafkaTemplate.send("orders", "order-2"));

        assertThat(listener.latch().await(30, TimeUnit.SECONDS))
                .as("both records must arrive")
                .isTrue();
        assertThat(listener.observed())
                .as("the listener asked TenantContext, and never read a header itself")
                .containsExactlyInAnyOrder("acme", "globex");
    }

    @Test
    @DisplayName("a record without a tenant does not inherit the previous record's")
    void aRecordWithoutATenantDoesNotInheritTheLastOne() throws Exception {
        listener.expect(1);
        TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                kafkaTemplate.send("orders", "order-with-tenant"));
        assertThat(listener.latch().await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.observed())
                .as("otherwise the assertion below is vacuous")
                .containsExactly("acme");

        listener.expect(1);
        TenantContext.clear();
        kafkaTemplate.send("orders", "order-without-tenant");

        assertThat(listener.latch().await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.observed())
                .as("the listener thread kept acme from the previous record — a "
                    + "cross-tenant write, not a missing read")
                .containsExactly("acme", "none");
    }

    @Test
    @DisplayName("a batch spanning two tenants scopes each record separately")
    void batchListenerScopesEachRecordSeparately() throws Exception {
        batchListener.expect(2);

        TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                kafkaTemplate.send("batch-orders", "a"));
        TenantContext.runWithTenant(TenantScope.of("globex"), () ->
                kafkaTemplate.send("batch-orders", "g"));

        assertThat(batchListener.latch().await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(batchListener.observed())
                .as("binding the batch to its first record's tenant would make both read 'acme'")
                .containsExactlyInAnyOrder("a=acme", "g=globex");
    }
}
