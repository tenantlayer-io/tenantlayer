package io.tenantlayer.support;

import io.tenantlayer.core.TenantTaskDecorator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootApplication
@EnableAsync
public class TestApplication {

    /**
     * Replaces Boot's autoconfigured executor so @Async work carries the tenant.
     * TODO: the starter's autoconfiguration should apply this decorator for the user.
     */
    @Bean
    ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(new TenantTaskDecorator());
        // Exactly one worker, so the leak test is guaranteed to reuse the same thread
        // rather than reuse it only sometimes.
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("tenant-async-");
        executor.initialize();
        return executor;
    }
}
