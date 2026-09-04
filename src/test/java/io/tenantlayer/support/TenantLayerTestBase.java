package io.tenantlayer.support;

import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.test.IsolationAssertions;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(classes = TestApplication.class)
@Import(TenantLayerTestBase.DataSources.class)
public abstract class TenantLayerTestBase {

    @Autowired
    protected DataSource applicationDataSource;

    @BeforeAll
    static void prepareDatabase() {
        PostgresSupport.resetSchema();
    }

    @BeforeEach
    void bindAssertions() {
        IsolationAssertions.bind(applicationDataSource, PostgresSupport.privileged());
        IsolationAssertions.bindTable("documents", "tenant_id");
    }

    @TestConfiguration
    static class DataSources {

        /**
         * Pool of 1. Every request in a test reuses the same physical connection, so a
         * tenant setting left behind on it is guaranteed to be observed rather than
         * occasionally observed.
         */
        @Bean
        @Primary
        DataSource applicationDataSource() {
            return new TenantAwareDataSource(PostgresSupport.applicationPool(1));
        }
    }
}
