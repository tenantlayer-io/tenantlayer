package io.tenantlayer.migration;

import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.strategy.TenantConnectionStrategy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Feature 64 — runs Flyway across tenants.
 *
 * <h2>Once there is more than one schema, migrate stops being one command</h2>
 *
 * Under a shared schema there is a single set of tables and a single migration history.
 * Under schema-per-tenant there are N of each, and they can drift: a migration that fails
 * for one tenant leaves that tenant behind while everyone else moves on. This runner exists
 * to make that visible rather than surprising.
 *
 * <h2>It asks the strategy, rather than assuming</h2>
 *
 * {@link TenantConnectionStrategy#schemaFor} returns empty when tenants share a schema. In
 * that case migrations run <strong>once</strong>, not once per tenant — looping would
 * replay the same migrations against the same tables and Flyway would rightly refuse.
 * Built against the strategy rather than against a single target, so
 * database-per-tenant slots in later without a rewrite.
 *
 * <h2>Migrations bypass the tenant-aware DataSource</h2>
 *
 * Deliberately. That wrapper sets the tenant or the search_path on every checkout from
 * whatever is in the context, and during a migration the context is empty or belongs to
 * whichever tenant happens to be bound. Flyway is told the schema explicitly instead, on
 * the underlying pool.
 *
 * <h2>One tenant failing does not stop the others</h2>
 *
 * A migration that aborts on the first bad tenant leaves everyone after it in the list
 * unmigrated, and which ones those are depends on alphabetical order. Every tenant is
 * attempted; failures are collected and thrown at the end naming them.
 */
public class TenantMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationRunner.class);

    private final DataSource dataSource;
    private final TenantRegistry registry;
    private final TenantConnectionStrategy strategy;
    private final List<String> locations;
    private final boolean baselineOnMigrate;

    public TenantMigrationRunner(DataSource dataSource, TenantRegistry registry,
                                 TenantConnectionStrategy strategy, List<String> locations,
                                 boolean baselineOnMigrate) {
        this.dataSource = unwrap(dataSource);
        this.registry = registry;
        this.strategy = strategy;
        this.locations = List.copyOf(locations);
        this.baselineOnMigrate = baselineOnMigrate;
    }

    /**
     * Flyway must not go through the tenant-aware wrapper, which would set a search_path
     * or a tenant from whatever is in the context and fight the schema we are about to
     * name explicitly.
     */
    private static DataSource unwrap(DataSource dataSource) {
        DataSource current = dataSource;
        while (current instanceof DelegatingDataSource delegating
                && delegating.getTargetDataSource() != null) {
            current = delegating.getTargetDataSource();
        }
        return current;
    }

    /** Migrates every active tenant, or the shared schema once. */
    public MigrationOutcome migrateAll() {
        List<String> tenants = registry.activeTenantIds();

        if (strategy.schemaFor("probe").isEmpty()) {
            // One shared schema. Running per tenant would replay the same migrations
            // against the same tables, which Flyway would refuse — correctly.
            log.info("strategy '{}' shares one schema; migrating once for {} tenants",
                    strategy.name(), tenants.size());
            return runOne("(shared)", null, tenants.size());
        }

        Map<String, Integer> migrated = new LinkedHashMap<>();
        Map<String, Throwable> failures = new LinkedHashMap<>();

        for (String tenant : tenants) {
            try {
                MigrationOutcome one = runOne(tenant, strategy.schemaFor(tenant).orElseThrow(), 1);
                migrated.putAll(one.migrated());
            } catch (TenantMigrationException e) {
                failures.putAll(e.outcome().failures());
            } catch (RuntimeException e) {
                log.warn("migration failed for tenant '{}'", tenant, e);
                failures.put(tenant, e);
            }
        }

        MigrationOutcome outcome = new MigrationOutcome(migrated, failures);
        if (!outcome.isSuccessful()) {
            throw new TenantMigrationException(outcome, tenants.size());
        }
        return outcome;
    }

    /** Migrates one tenant. */
    public MigrationOutcome migrate(String tenantId) {
        String schema = strategy.schemaFor(tenantId).orElse(null);
        return runOne(tenantId, schema, 1);
    }

    private MigrationOutcome runOne(String label, String schema, int attempted) {
        Map<String, Integer> migrated = new LinkedHashMap<>();
        Map<String, Throwable> failures = new LinkedHashMap<>();

        try {
            var configuration = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(locations.toArray(String[]::new))
                    .baselineOnMigrate(baselineOnMigrate);

            if (schema != null) {
                // createSchemas so a newly provisioned tenant does not need the schema
                // created out of band first.
                configuration = configuration.schemas(schema).defaultSchema(schema)
                        .createSchemas(true);
            }

            MigrateResult result = configuration.load().migrate();
            migrated.put(label, result.migrationsExecuted);
            log.info("migrated {} ({} applied)", label, result.migrationsExecuted);
        } catch (RuntimeException e) {
            log.warn("migration failed for {}", label, e);
            failures.put(label, e);
        }

        MigrationOutcome outcome = new MigrationOutcome(migrated, failures);
        if (!outcome.isSuccessful()) {
            throw new TenantMigrationException(outcome, attempted);
        }
        return outcome;
    }

    public Optional<String> schemaFor(String tenantId) {
        return strategy.schemaFor(tenantId);
    }
}
