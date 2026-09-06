package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.migration.MigrationOutcome;
import io.tenantlayer.migration.TenantMigrationRunner;
import io.tenantlayer.registry.JdbcTenantRegistry;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.strategy.RowLevelSecurityStrategy;
import io.tenantlayer.strategy.SchemaPerTenantStrategy;
import io.tenantlayer.support.PostgresSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature 64 — Flyway across tenants.
 *
 * The load-bearing test is {@link #sharedSchemaMigratesOnceNotPerTenant()}. Running a
 * per-tenant loop under a shared schema would replay the same migrations against the same
 * tables, and the fact that Flyway would refuse is not a defence — it means a runner that
 * assumes one shape breaks loudly under the other. Asking the strategy is the point.
 */
class TenantMigrationTest {

    // Deliberately NOT under classpath:db/migration. That is Spring Boot's default
    // Flyway location, and simply having flyway-core on the classpath makes Boot
    // auto-configure a single-schema migration that would run these against the shared
    // schema at every context start — which is the same trap a real user falls into.
    private static final List<String> LOCATIONS = List.of("classpath:tenant-migrations");

    private static DataSource pool;
    private static TenantRegistry registry;

    @BeforeAll
    static void start() {
        PostgresSupport.resetSchema();
        pool = PostgresSupport.applicationPool(3);
        registry = new JdbcTenantRegistry(PostgresSupport.privileged());
    }

    @BeforeEach
    void cleanSchemas() {
        PostgresSupport.executeAsAdmin("""
                drop schema if exists m_acme cascade;
                drop schema if exists m_globex cascade;
                drop schema if exists m_shared cascade;
                create schema m_shared;
                grant usage, create on schema m_shared to app_user;
                grant create on database tenantlayer to app_user;
                """);
    }

    private TenantMigrationRunner schemaPerTenantRunner() {
        return new TenantMigrationRunner(PostgresSupport.privileged(), registry,
                new SchemaPerTenantStrategy(pool, "m_"), LOCATIONS, false);
    }

    private long columnCount(String schema) {
        String sql = schema == null
                ? "select count(*) from information_schema.columns where table_name = 'reports'"
                : "select count(*) from information_schema.columns where table_schema = '"
                  + schema + "' and table_name = 'reports'";
        try (Connection connection = PostgresSupport.privileged().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("each tenant gets its own schema, migrated independently")
    void eachTenantSchemaIsMigrated() {
        MigrationOutcome outcome = schemaPerTenantRunner().migrateAll();

        assertThat(outcome.isSuccessful()).isTrue();
        assertThat(outcome.migrated().keySet())
                .as("only active tenants; initech is suspended")
                .containsExactly("acme", "globex");

        // Four columns: id, tenant_id, title, and status from V2.
        assertThat(columnCount("m_acme")).isEqualTo(4);
        assertThat(columnCount("m_globex")).isEqualTo(4);
    }

    @Test
    @DisplayName("both migrations are applied, so V2 really ran")
    void allMigrationsAreApplied() {
        MigrationOutcome outcome = schemaPerTenantRunner().migrateAll();

        assertThat(outcome.totalMigrationsApplied())
                .as("two migrations, two tenants")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("a shared schema migrates once, not once per tenant")
    void sharedSchemaMigratesOnceNotPerTenant() {
        // A pool whose default schema is clean, because under a shared-schema strategy
        // the runner names no schema and Flyway uses the connection's default. Pointing
        // it at the fixture-laden public schema would test Flyway's baseline semantics
        // rather than the behaviour under test.
        var sharedPool = PostgresSupport.privilegedPoolInSchema("m_shared");
        TenantMigrationRunner runner = new TenantMigrationRunner(
                sharedPool, registry, new RowLevelSecurityStrategy(pool), LOCATIONS, false);

        MigrationOutcome outcome = runner.migrateAll();

        assertThat(outcome.isSuccessful())
                .as("looping per tenant here would replay the same migrations against the "
                    + "same tables and Flyway would refuse")
                .isTrue();
        assertThat(outcome.migrated().keySet()).containsExactly("(shared)");
        assertThat(outcome.totalMigrationsApplied())
                .as("two migrations, applied once — not once per tenant")
                .isEqualTo(2);
        assertThat(columnCount("m_shared")).isEqualTo(4);
    }

    @Test
    @DisplayName("running twice is a no-op, not a failure")
    void migrationIsIdempotent() {
        schemaPerTenantRunner().migrateAll();
        MigrationOutcome second = schemaPerTenantRunner().migrateAll();

        assertThat(second.isSuccessful()).isTrue();
        assertThat(second.totalMigrationsApplied())
                .as("nothing left to apply on the second run")
                .isZero();
    }

    @Test
    @DisplayName("one tenant's schema is untouched by another's migration")
    void tenantsMigrateIndependently() {
        schemaPerTenantRunner().migrate("acme");

        assertThat(columnCount("m_acme")).isEqualTo(4);
        assertThat(columnCount("m_globex"))
                .as("migrating acme must not create globex's tables")
                .isZero();
    }
}
