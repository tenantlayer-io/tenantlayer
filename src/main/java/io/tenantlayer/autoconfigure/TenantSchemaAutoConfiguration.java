package io.tenantlayer.autoconfigure;

import io.tenantlayer.check.IsolationChecker;
import io.tenantlayer.check.IsolationFinding;
import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.schema.RlsPolicyGenerator;
import io.tenantlayer.schema.TenantScopedEntityScanner;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.tenantlayer.strategy.TenantConnectionStrategy;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
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

    @Bean
    @ConditionalOnBean(TenantScopedEntityScanner.class)
    @ConditionalOnMissingBean
    IsolationChecker isolationChecker(TenantScopedEntityScanner scanner, DataSource dataSource) {
        /* The unwrapped datasource: this runs at start-up, when no tenant is bound, and the
           questions it asks are about the schema rather than about anyone's rows. */
        return new IsolationChecker(scanner, TenantAwareDataSource.unwrap(dataSource));
    }

    /**
     * Feature 31 — reports at start-up, and never prevents it.
     *
     * <p>Runs on {@link ApplicationReadyEvent} rather than during bean creation so that a
     * slow or unreachable database delays a log line rather than the application.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> tenantLayerIsolationCheckReporter(
            ObjectProvider<IsolationChecker> checker,
            ObjectProvider<TenantConnectionStrategy> strategy,
            TenantLayerProperties properties) {

        return event -> {
            if (!properties.getCheck().isEnabled()) {
                return;
            }
            IsolationChecker isolationChecker = checker.getIfAvailable();
            if (isolationChecker == null) {
                return;
            }
            /* Database-per-tenant does not use policies — the pool is the isolation — so
               every table would be reported as unprotected, which is noise rather than news. */
            TenantConnectionStrategy selected = strategy.getIfAvailable();
            if (selected != null && !selected.expectsRowLevelSecurity()) {
                return;
            }
            report(isolationChecker.check());
        };
    }

    private static void report(List<IsolationFinding> findings) {
        Logger log = LoggerFactory.getLogger(IsolationChecker.class);
        if (findings.isEmpty()) {
            log.info("isolation check: every tenant-scoped table has an enforced policy");
            return;
        }
        long notEnforced = findings.stream()
                .filter(f -> f.severity() == IsolationFinding.Severity.NOT_ENFORCED)
                .count();
        if (notEnforced > 0) {
            log.warn("isolation check: {} of {} findings mean isolation is NOT being enforced",
                    notEnforced, findings.size());
        } else {
            log.warn("isolation check: {} finding(s)", findings.size());
        }
        for (IsolationFinding finding : findings) {
            log.warn("  {}", finding);
        }
    }
}
