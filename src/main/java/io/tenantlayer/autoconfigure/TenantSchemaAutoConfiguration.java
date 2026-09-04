package io.tenantlayer.autoconfigure;

import io.tenantlayer.schema.RlsPolicyGenerator;
import io.tenantlayer.schema.TenantScopedEntityScanner;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Features 21 and 30 — the scanner and the policy generator, once Hibernate's metamodel
 * exists to be asked.
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(EntityManagerFactory.class)
@EnableConfigurationProperties(TenantLayerProperties.class)
public class TenantSchemaAutoConfiguration {

    @Bean
    @ConditionalOnBean(EntityManagerFactory.class)
    @ConditionalOnMissingBean
    TenantScopedEntityScanner tenantScopedEntityScanner(
            EntityManagerFactory entityManagerFactory, TenantLayerProperties properties) {

        TenantLayerProperties.Schema schema = properties.getSchema();
        return new TenantScopedEntityScanner(entityManagerFactory, schema.getTenantColumn(),
                schema.getIncludes(), schema.getExcludes());
    }

    @Bean
    @ConditionalOnMissingBean
    RlsPolicyGenerator rlsPolicyGenerator() {
        return new RlsPolicyGenerator();
    }
}
