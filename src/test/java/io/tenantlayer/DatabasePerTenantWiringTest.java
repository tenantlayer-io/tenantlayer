package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.tenantlayer.autoconfigure.TenantLayerAutoConfiguration;
import io.tenantlayer.autoconfigure.TenantRegistryAutoConfiguration;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.core.NoTenantException;
import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.strategy.TenantDataSourceProvider;
import io.tenantlayer.strategy.UnknownTenantDatabaseException;
import io.tenantlayer.support.PostgresSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The wiring, not the strategy.
 *
 * <p>{@link DatabasePerTenantTest} builds the strategy by hand, which proves the routing but
 * says nothing about whether an application that merely sets a property gets it. Everything
 * between a property and a connection — building the provider from configuration, resolving
 * the registry lazily so the datasource post-processor does not close a circular dependency,
 * and wrapping the application's own datasource — is only exercised here.
 */
class DatabasePerTenantWiringTest {

    @BeforeAll
    static void prepareDatabases() {
        PostgresSupport.start();
        PostgresSupport.createDatabase("wired_acme");
        PostgresSupport.createDatabase("wired_globex");
        seed("wired_acme", "acme row");
        seed("wired_globex", "globex row");

        // The registry lives in the shared database, not in any tenant's.
        PostgresSupport.executeAsAdmin("""
                drop table if exists tenantlayer_tenants;
                create table tenantlayer_tenants (
                    tenant_id      varchar(64) primary key,
                    status         varchar(16) not null default 'ACTIVE',
                    region         varchar(64),
                    tenant_group   varchar(64),
                    datasource_ref varchar(128),
                    metadata       jsonb not null default '{}'::jsonb
                );
                insert into tenantlayer_tenants (tenant_id, status) values
                    ('acme', 'ACTIVE'), ('globex', 'ACTIVE');
                grant select, insert, update, delete on tenantlayer_tenants to app_user;
                """);
    }

    private static void seed(String database, String body) {
        try (var pool = PostgresSupport.privilegedPoolInDatabase(database);
                Connection c = pool.getConnection();
                Statement s = c.createStatement()) {
            s.execute("create table if not exists notes (id bigserial primary key, body varchar(255))");
            s.execute("truncate notes");
            s.execute("insert into notes (body) values ('" + body + "')");
        } catch (Exception e) {
            throw new IllegalStateException("could not seed " + database, e);
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        TenantLayerAutoConfiguration.class,
                        TenantRegistryAutoConfiguration.class))
                .withUserConfiguration(MainDataSource.class)
                .withPropertyValues(
                        "tenantlayer.strategy=DATABASE_PER_TENANT",
                        "tenantlayer.databases.acme.url=" + PostgresSupport.urlForDatabase("wired_acme"),
                        "tenantlayer.databases.acme.username=" + PostgresSupport.adminUser(),
                        "tenantlayer.databases.acme.password=" + PostgresSupport.adminPassword(),
                        "tenantlayer.databases.globex.url=" + PostgresSupport.urlForDatabase("wired_globex"),
                        "tenantlayer.databases.globex.username=" + PostgresSupport.adminUser(),
                        "tenantlayer.databases.globex.password=" + PostgresSupport.adminPassword());
    }

    @Test
    @DisplayName("the context starts: no circular dependency between datasource and registry")
    void contextStarts() {
        runner().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("setting one property is enough to route by tenant")
    void propertiesAloneRouteByTenant() {
        runner().run(context -> {
            DataSource ds = context.getBean(DataSource.class);
            assertThat(ds).isInstanceOf(TenantAwareDataSource.class);

            assertThat(read(ds, "acme")).isEqualTo("acme row");
            assertThat(read(ds, "globex")).isEqualTo("globex row");
        });
    }

    @Test
    @DisplayName("an unknown tenant fails closed through the wired datasource too")
    void unknownTenantFailsClosedWhenWired() {
        runner().run(context -> {
            DataSource ds = context.getBean(DataSource.class);
            TenantContext.runWithTenant(TenantScope.of("stranger"), () ->
                    assertThatThrownBy(ds::getConnection)
                            .isInstanceOf(UnknownTenantDatabaseException.class));
            TenantContext.clear();
            assertThatThrownBy(ds::getConnection).isInstanceOf(NoTenantException.class);
        });
    }

    @Test
    @DisplayName("a user-supplied provider bean replaces the configured databases")
    void suppliedProviderWins() {
        runner().withUserConfiguration(OnlyAcme.class).run(context -> {
            DataSource ds = context.getBean(DataSource.class);
            assertThat(read(ds, "acme")).isEqualTo("acme row");
            TenantContext.runWithTenant(TenantScope.of("globex"), () ->
                    assertThatThrownBy(ds::getConnection)
                            .as("the bean knows only acme, so globex must fail even though it is configured")
                            .isInstanceOf(UnknownTenantDatabaseException.class));
        });
    }

    @Test
    @DisplayName("the registry still answers with no tenant bound, under this strategy")
    void registryWorksWithNoTenantBound() {
        runner().run(context -> {
            TenantContext.clear();
            TenantRegistry registry = context.getBean(TenantRegistry.class);
            /* The registry answers "who are the tenants?", asked before any tenant exists.
               Routed through the tenant-aware datasource it would throw NoTenantException
               under this strategy — which is why it reads the unwrapped one. */
            assertThat(registry.activeTenantIds()).containsExactlyInAnyOrder("acme", "globex");
        });
    }

    private static String read(DataSource ds, String tenant) {
        return TenantContext.callWithTenant(TenantScope.of(tenant), () -> {
            try (Connection c = ds.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("select body from notes")) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class MainDataSource {
        /** Stands in for the application's own datasource, the one TenantLayer wraps. */
        @Bean
        DataSource dataSource() {
            return PostgresSupport.applicationPool(2);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OnlyAcme {
        @Bean
        TenantDataSourceProvider tenantDataSourceProvider() {
            HikariDataSource acme = PostgresSupport.privilegedPoolInDatabase("wired_acme");
            return tenantId -> "acme".equals(tenantId) ? Optional.of(acme) : Optional.empty();
        }
    }
}
