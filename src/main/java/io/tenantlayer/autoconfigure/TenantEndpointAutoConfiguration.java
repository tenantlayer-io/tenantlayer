package io.tenantlayer.autoconfigure;

import io.tenantlayer.actuate.TenantsEndpoint;
import io.tenantlayer.registry.TenantProvisioning;
import io.tenantlayer.registry.TenantRegistry;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Feature 51 — registers the tenants endpoint when actuator is present and it has been
 * both enabled and exposed.
 *
 * <p>The guard is on the class rather than the {@code @Bean} method. Spring introspects the
 * return type of every {@code @Bean} method while deciding whether a configuration applies,
 * which happens before a method-level {@code @ConditionalOnClass} is evaluated — so a method
 * returning {@link TenantsEndpoint} would throw {@code NoClassDefFoundError} on a classpath
 * without actuator, however carefully it were annotated.
 */
@AutoConfiguration(after = TenantRegistryAutoConfiguration.class)
@ConditionalOnClass({Endpoint.class, ConditionalOnAvailableEndpoint.class})
public class TenantEndpointAutoConfiguration {

    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = TenantsEndpoint.class)
    @ConditionalOnBean({TenantRegistry.class, TenantProvisioning.class})
    @ConditionalOnMissingBean
    TenantsEndpoint tenantsEndpoint(TenantRegistry registry, TenantProvisioning provisioning) {
        return new TenantsEndpoint(registry, provisioning);
    }
}
