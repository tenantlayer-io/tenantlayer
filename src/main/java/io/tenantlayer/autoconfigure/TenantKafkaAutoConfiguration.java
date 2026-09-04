package io.tenantlayer.autoconfigure;

import io.tenantlayer.kafka.TenantBatchInterceptor;
import io.tenantlayer.kafka.TenantProducerInterceptor;
import io.tenantlayer.kafka.TenantRecordInterceptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Features 17, 18 and 92 — the tenant travels in a Kafka record header, end to end.
 *
 * <p>Boot picks up a {@code RecordInterceptor} and a {@code BatchInterceptor} bean and
 * applies them to the listener container factory it builds, so the consumer side needs no
 * customizer of its own.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, ProducerConfig.class})
@ConditionalOnProperty(prefix = "tenantlayer.kafka", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TenantKafkaAutoConfiguration {

    /**
     * Appends rather than replaces. An application that has its own producer interceptor
     * — for tracing, say — would otherwise lose it the moment TenantLayer is added, and
     * would have no obvious reason to connect the two.
     */
    @Bean
    DefaultKafkaProducerFactoryCustomizer tenantLayerProducerInterceptorCustomizer() {
        return factory -> {
            Map<String, Object> configs = factory.getConfigurationProperties();
            List<String> interceptors =
                    new ArrayList<>(existing(configs.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG)));
            String ours = TenantProducerInterceptor.class.getName();
            if (!interceptors.contains(ours)) {
                interceptors.add(ours);
            }
            factory.updateConfigs(
                    Map.of(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, interceptors));
        };
    }

    private static List<String> existing(Object configured) {
        if (configured == null) {
            return List.of();
        }
        if (configured instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return Arrays.stream(String.valueOf(configured).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    @Bean
    @ConditionalOnMissingBean
    RecordInterceptor<Object, Object> tenantLayerRecordInterceptor() {
        return new TenantRecordInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    BatchInterceptor<Object, Object> tenantLayerBatchInterceptor() {
        return new TenantBatchInterceptor();
    }
}
