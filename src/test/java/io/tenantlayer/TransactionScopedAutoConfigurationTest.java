package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import jakarta.persistence.EntityManagerFactory;

class TransactionScopedAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantLayerAutoConfiguration.class))
            .withUserConfiguration(TransactionConfig.class)
            .withPropertyValues("tenantlayer.strategy=ROW_LEVEL_SECURITY_TRANSACTION_SCOPED");

    @Test
    @DisplayName("autoconfiguration attaches the Spring transaction lifecycle listener")
    void attachesTransactionListener() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DataSourceTransactionManager.class);
            AbstractPlatformTransactionManager manager =
                    context.getBean(DataSourceTransactionManager.class);
            assertThat(manager.getTransactionExecutionListeners()).hasSize(1);
        });
    }

    @Test
    @DisplayName("autoconfiguration attaches the listener to a JPA manager with the wrapped DataSource")
    void attachesJpaTransactionListener() {
        runner.withUserConfiguration(JpaTransactionConfig.class).run(context -> {
            JpaTransactionManager manager = context.getBean(JpaTransactionManager.class);
            assertThat(manager.getTransactionExecutionListeners()).hasSize(1);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionConfig {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JpaTransactionConfig {

        @Bean
        EntityManagerFactory entityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }

        @Bean
        JpaTransactionManager jpaTransactionManager(
                EntityManagerFactory entityManagerFactory, DataSource dataSource) {
            JpaTransactionManager manager = new JpaTransactionManager(entityManagerFactory);
            manager.setDataSource(dataSource);
            return manager;
        }
    }
}
