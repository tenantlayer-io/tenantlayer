package io.tenantlayer.autoconfigure;

import io.micrometer.observation.ObservationFilter;
import io.tenantlayer.metrics.TenantObservationFilter;
import io.tenantlayer.metrics.TenantTagLimiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Feature 82 — the tenant tag on metrics.
 *
 * <h2>Why this is its own class</h2>
 *
 * The guard is on the class, not on the {@code @Bean} method. Spring introspects the return
 * type of every {@code @Bean} method while deciding whether a configuration applies, which
 * happens <em>before</em> a method-level {@code @ConditionalOnClass} is evaluated — so a
 * method returning {@link ObservationFilter} would throw {@code NoClassDefFoundError} on a
 * classpath without Micrometer, however carefully it was annotated.
 *
 * <p>That is not hypothetical here: micrometer-observation arrives with spring-kafka or
 * actuator, both optional, so plenty of consumers will not have it.
 */
@AutoConfiguration
@ConditionalOnClass(ObservationFilter.class)
@ConditionalOnProperty(prefix = "tenantlayer.metrics", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantLayerProperties.class)
public class TenantMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TenantTagLimiter tenantTagLimiter(TenantLayerProperties properties) {
        TenantLayerProperties.Metrics metrics = properties.getMetrics();
        return new TenantTagLimiter(metrics.getMaxTenants(), metrics.getOverflowValue());
    }

    @Bean
    @ConditionalOnMissingBean
    ObservationFilter tenantObservationFilter(TenantTagLimiter limiter) {
        return new TenantObservationFilter(limiter);
    }
}
