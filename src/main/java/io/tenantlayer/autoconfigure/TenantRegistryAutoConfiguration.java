package io.tenantlayer.autoconfigure;

import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.migration.TenantMigrationRunner;
import io.tenantlayer.registry.JdbcTenantRegistry;
import io.tenantlayer.registry.TenantProvisioning;
import io.tenantlayer.registry.TenantProvisioningHook;
import io.tenantlayer.registry.TenantRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
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
        /* Deliberately the unwrapped datasource. The registry answers "who are the
           tenants?", which is asked before any tenant is bound — routing that question by
           the acting tenant is a contradiction. It happens to work through the wrapper
           under row-level security, and throws under database-per-tenant, which is the
           same bug either way. */
        return new JdbcTenantRegistry(
                TenantAwareDataSource.unwrap(dataSource), properties.getRegistry().getTable());
    }

    /**
     * Feature 67 — onboarding, for whichever service owns signing up.
     *
     * <p>The migration runner is optional: it only exists when Flyway is on the classpath,
     * and under a shared store there is nothing per-tenant to migrate anyway. Hooks are
     * whatever the application defines, in {@code order()}.
     */
    @Bean
    @ConditionalOnBean(TenantRegistry.class)
    @ConditionalOnMissingBean
    TenantProvisioning tenantProvisioning(
            TenantRegistry registry,
            ObjectProvider<TenantMigrationRunner> migrations,
            ObjectProvider<TenantProvisioningHook> hooks) {

        return new TenantProvisioning(
                registry, migrations.getIfAvailable(), hooks.orderedStream().toList());
    }
}
