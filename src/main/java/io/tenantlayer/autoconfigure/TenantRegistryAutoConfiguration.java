package io.tenantlayer.autoconfigure;

import io.tenantlayer.registry.JdbcTenantRegistry;
import io.tenantlayer.registry.TenantRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Feature 50 — exposes the registry once a DataSource exists.
 *
 * Separate from {@link TenantLayerAutoConfiguration} purely because of ordering: that one
 * runs <em>before</em> {@link DataSourceAutoConfiguration} so its bean post-processor can
 * wrap the DataSource, which makes it the wrong place to also <em>consume</em> a
 * DataSource. This one runs after, where asking for that bean is safe.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "tenantlayer.registry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantLayerProperties.class)
public class TenantRegistryAutoConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    TenantRegistry tenantRegistry(DataSource dataSource, TenantLayerProperties properties) {
        return new JdbcTenantRegistry(dataSource, properties.getRegistry().getTable());
    }
}
