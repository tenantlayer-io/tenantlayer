package com.acme.orders;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** Plain Spring caching. TenantLayer wraps whatever CacheManager Boot builds. */
@Configuration
@EnableCaching
public class CacheConfig {
}
