package io.tenantlayer.kafkasupport;

import io.tenantlayer.autoconfigure.TenantRegistryAutoConfiguration;
import io.tenantlayer.autoconfigure.TenantSchemaAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Kafka only. The database autoconfigurations are excluded because this test has no
 * Postgres and needs none — the tenant travelling in a record header is independent of
 * where the rows eventually land.
 */
@Configuration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        TenantRegistryAutoConfiguration.class,
        TenantSchemaAutoConfiguration.class})
@EnableKafka
public class KafkaTestApplication {

    /**
     * Declared rather than component-scanned. Scanning from this package would also pull
     * in the JPA test fixtures under io.tenantlayer.support and fail for want of a
     * database this test does not have.
     */
    @Bean
    RecordingListener recordingListener() {
        return new RecordingListener();
    }

    @Bean
    BatchRecordingListener batchRecordingListener() {
        return new BatchRecordingListener();
    }

    /**
     * Built through Boot's own configurer rather than by hand, so the BatchInterceptor
     * bean TenantLayer contributes is applied the same way it would be in a real
     * application. Constructing the factory directly would test nothing about the
     * autoconfiguration.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> batchFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        return factory;
    }
}
