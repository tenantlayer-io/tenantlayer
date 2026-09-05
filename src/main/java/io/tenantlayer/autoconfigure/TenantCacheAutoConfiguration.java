package io.tenantlayer.autoconfigure;

import io.tenantlayer.cache.TenantAwareCacheManager;
import io.tenantlayer.cache.TenantCacheEvictor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;

/**
 * Features 96 and 97 — wraps whatever {@link CacheManager} the application defines.
 *
 * <p>Wrapping rather than replacing, for the same reason the DataSource is wrapped: users
 * keep their own cache configuration — Caffeine, Redis, sizes, TTLs — and change nothing.
 */
@AutoConfiguration(before = CacheAutoConfiguration.class)
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(prefix = "tenantlayer.cache", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenantLayerProperties.class)
public class TenantCacheAutoConfiguration {

    /**
     * Static, returning a raw BeanPostProcessor, so the enclosing configuration is not
     * forced to initialise before the caches it needs to wrap exist.
     */
    @Bean
    static BeanPostProcessor tenantLayerCacheManagerPostProcessor(
            org.springframework.beans.factory.ObjectProvider<TenantLayerProperties> properties) {

        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName)
                    throws BeansException {
                if (bean instanceof CacheManager manager
                        && !(bean instanceof TenantAwareCacheManager)) {
                    TenantLayerProperties props = properties.getIfAvailable();
                    return new TenantAwareCacheManager(
                            manager, props == null ? java.util.Set.of() : props.getCache().getShared());
                }
                return bean;
            }
        };
    }

    @Bean
    @ConditionalOnBean(CacheManager.class)
    @ConditionalOnMissingBean
    TenantCacheEvictor tenantCacheEvictor(CacheManager cacheManager) {
        return new TenantCacheEvictor(cacheManager);
    }
}
