package io.tenantlayer.strategy;

import io.tenantlayer.registry.TenantRegistration;
import io.tenantlayer.registry.TenantRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;

/**
 * The stock {@link TenantDataSourceProvider}: databases declared in configuration, pools
 * built the first time a tenant actually asks for one.
 *
 * <h2>Keyed by reference, not by tenant</h2>
 *
 * Tenants are mapped to a database <em>reference</em> rather than straight to a URL, because
 * tenants are routinely grouped onto shards — a hundred small tenants on one database, a
 * large one alone. {@link TenantRegistration#datasourceRef()} carries that grouping, and
 * when a tenant has no reference the tenant id is used as its own, which is the common
 * one-database-per-tenant case with nothing extra to configure.
 *
 * <h2>Lazy, and bounded on purpose</h2>
 *
 * A pool per tenant is expensive: each holds idle connections the database must account for.
 * Pools are therefore built on first use and never speculatively, and the number of them is
 * capped. Reaching the cap throws rather than evicting: an idle pool cannot be closed safely
 * without knowing whether a connection from it is still in flight, and quietly recycling
 * pools would turn a capacity problem into intermittent failures under load. Failing at the
 * boundary says plainly that the deployment has outgrown this provider.
 *
 * <p>Pool lifecycle at scale is explicitly out of scope, per the issue. This is correctness
 * for tens of tenants, not thousands.
 */
public class ConfiguredTenantDataSourceProvider implements TenantDataSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredTenantDataSourceProvider.class);

    /** Enough for the deployments this provider is meant for, low enough to notice. */
    public static final int DEFAULT_MAX_POOLS = 50;

    private final Map<String, TenantDatabase> databases;
    /* A supplier rather than the registry itself: the registry reads from a datasource, and
       this provider is built while datasources are still being wrapped. Holding the instance
       would close that loop into a circular dependency; resolving it on first use does not,
       because by then the context is up. */
    private final Supplier<TenantRegistry> registry;
    private final int maxPools;
    private final Map<String, DataSource> pools = new ConcurrentHashMap<>();

    public ConfiguredTenantDataSourceProvider(Map<String, TenantDatabase> databases) {
        this(databases, () -> null, DEFAULT_MAX_POOLS);
    }

    public ConfiguredTenantDataSourceProvider(
            Map<String, TenantDatabase> databases, TenantRegistry registry, int maxPools) {
        this(databases, () -> registry, maxPools);
    }

    public ConfiguredTenantDataSourceProvider(
            Map<String, TenantDatabase> databases, Supplier<TenantRegistry> registry, int maxPools) {
        this.databases = databases == null ? Map.of() : Map.copyOf(databases);
        this.registry = registry == null ? () -> null : registry;
        this.maxPools = maxPools > 0 ? maxPools : DEFAULT_MAX_POOLS;
    }

    @Override
    public Optional<DataSource> forTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        String ref = referenceFor(tenantId);
        TenantDatabase database = databases.get(ref);
        if (database == null) {
            return Optional.empty();
        }

        /* computeIfAbsent so two requests for a cold tenant cannot race into two pools —
           the loser of the race would leak connections nobody ever closes. */
        return Optional.of(pools.computeIfAbsent(ref, key -> {
            if (pools.size() >= maxPools) {
                throw new IllegalStateException(
                        "refusing to open a pool for '" + key + "': already holding " + pools.size()
                                + " of a maximum " + maxPools
                                + ". Raise tenantlayer.databases-max-pools, or move to a provider that"
                                + " manages pool lifecycle.");
            }
            log.info("opening pool for database reference '{}'", key);
            return build(database);
        }));
    }

    /** A tenant's shard, or the tenant itself when it is not grouped onto one. */
    private String referenceFor(String tenantId) {
        TenantRegistry lookup = registry.get();
        if (lookup == null) {
            return tenantId;
        }
        return lookup.find(tenantId)
                .map(TenantRegistration::datasourceRef)
                .filter(ref -> ref != null && !ref.isBlank())
                .orElse(tenantId);
    }

    private DataSource build(TenantDatabase database) {
        DataSourceBuilder<?> builder = DataSourceBuilder.create().url(database.url());
        if (database.username() != null) {
            builder.username(database.username());
        }
        if (database.password() != null) {
            builder.password(database.password());
        }
        DataSource created = builder.build();
        if (database.maxPoolSize() != null && created instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            hikari.setMaximumPoolSize(database.maxPoolSize());
        }
        return created;
    }

    /** Visible for diagnostics and tests: how many pools are currently open. */
    public int openPools() {
        return pools.size();
    }

    @Override
    public void shutdown() {
        pools.values().forEach(ds -> {
            if (ds instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("failed to close a tenant pool", e);
                }
            }
        });
        pools.clear();
    }
}
