package io.tenantlayer.test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tenantlayer.core.TenantAwareDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Feature 105 — a Postgres container wired the way a tenancy test actually needs it.
 *
 * <h2>What this saves you from getting wrong</h2>
 *
 * Standing up a Postgres container is one line; standing up one that can <em>prove</em>
 * isolation is not. The trap is the connection you test through. A Testcontainers Postgres
 * hands you a superuser, and <strong>superusers bypass row-level security entirely</strong>
 * — so a test suite written against that connection has the policy in place, never
 * applied, and every isolation assertion passing for the wrong reason. Worse, it keeps
 * passing after someone deletes the policy.
 *
 * <p>So this exposes two DataSources and they are not interchangeable:
 * <ul>
 *   <li>{@link #applicationDataSource()} — a least-privileged role that is neither
 *       superuser nor table owner. This is what the code under test uses. It is subject
 *       to the policy, which is the entire point.</li>
 *   <li>{@link #privilegedDataSource()} — bypasses the policy. Use it only to seed data
 *       and to prove another tenant's rows genuinely exist, never as the subject of an
 *       assertion.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * static final TenantPostgres POSTGRES = TenantPostgres.start()
 *         .withTenantTable("documents", "title varchar(255) not null")
 *         .seed("documents", "acme",   "acme quarterly report")
 *         .seed("documents", "globex", "globex board minutes");
 *
 * @BeforeEach
 * void bind() {
 *     IsolationAssertions.bind(POSTGRES.applicationDataSource(), POSTGRES.privilegedDataSource());
 * }
 * }</pre>
 *
 * <p>The container is shared for the JVM and never explicitly stopped: Testcontainers'
 * Ryuk reaper removes it when the JVM exits, and starting one per test class costs several
 * seconds each for no benefit.
 */
public final class TenantPostgres {

    private static final String APP_USER = "tenantlayer_app";
    private static final String APP_PASSWORD = "tenantlayer_app_pwd";

    private static volatile TenantPostgres instance;

    private final PostgreSQLContainer<?> container;
    private final HikariDataSource privileged;
    private final Map<String, HikariDataSource> applicationPools = new LinkedHashMap<>();
    private final List<String> tenantTables = new ArrayList<>();

    private TenantPostgres(String image) {
        this.container = new PostgreSQLContainer<>(image)
                .withDatabaseName("tenantlayer")
                .withUsername("tenantlayer_admin")
                .withPassword("admin_pwd");
        this.container.start();
        this.privileged = pool("privileged", container.getUsername(), container.getPassword(), 4);
        execute("drop role if exists " + APP_USER + ";"
                + "create role " + APP_USER + " login password '" + APP_PASSWORD + "';"
                + "grant usage on schema public to " + APP_USER + ";");
    }

    /** Starts, or returns, the shared container. */
    public static TenantPostgres start() {
        return start("postgres:16");
    }

    public static synchronized TenantPostgres start(String image) {
        if (instance == null) {
            instance = new TenantPostgres(image);
        }
        return instance;
    }

    /**
     * Creates a tenant-scoped table with row-level security already configured correctly:
     * an index on the tenant column, {@code FORCE ROW LEVEL SECURITY} so the owner is
     * subject to it too, and a policy guarded with {@code nullif(..., '')} so that no
     * tenant matches no rows rather than erroring.
     *
     * @param columnDefinitions additional columns, e.g. {@code "title varchar(255) not null"}
     */
    public TenantPostgres withTenantTable(String table, String columnDefinitions) {
        return withTenantTable(table, "tenant_id", columnDefinitions);
    }

    public TenantPostgres withTenantTable(String table, String tenantColumn,
                                          String columnDefinitions) {
        execute("""
                drop table if exists %s;
                create table %s (
                    id bigserial primary key,
                    %s varchar(64) not null,
                    %s
                );
                create index idx_%s_%s on %s (%s);
                alter table %s enable row level security;
                alter table %s force row level security;
                create policy %s_tenant_isolation on %s
                    using (%s = nullif(current_setting('%s', true), ''));
                grant select, insert, update, delete on %s to %s;
                grant usage, select on all sequences in schema public to %s;
                """.formatted(
                table,
                table, tenantColumn, columnDefinitions,
                table, tenantColumn, table, tenantColumn,
                table,
                table,
                table, table,
                tenantColumn, TenantAwareDataSource.TENANT_SETTING,
                table, APP_USER,
                APP_USER));
        tenantTables.add(table);
        return this;
    }

    /** Creates a table with no policy — for testing the discriminator strategy. */
    public TenantPostgres withUnprotectedTable(String table, String columnDefinitions) {
        execute("""
                drop table if exists %s;
                create table %s (
                    id bigserial primary key,
                    tenant_id varchar(64) not null,
                    %s
                );
                grant select, insert, update, delete on %s to %s;
                grant usage, select on all sequences in schema public to %s;
                """.formatted(table, table, columnDefinitions, table, APP_USER, APP_USER));
        tenantTables.add(table);
        return this;
    }

    /**
     * Seeds one row on the privileged connection, which the policy does not apply to.
     * Column names are explicit rather than positional, because guessing them from
     * argument order is how a fixture silently writes the right values into the wrong
     * columns and the resulting test failure points anywhere but here.
     */
    public TenantPostgres seedRow(String table, String tenant, Map<String, Object> columns) {
        StringBuilder names = new StringBuilder("tenant_id");
        StringBuilder placeholders = new StringBuilder("?");
        for (String column : columns.keySet()) {
            names.append(", ").append(column);
            placeholders.append(", ?");
        }
        String sql = "insert into " + table + " (" + names + ") values (" + placeholders + ")";
        try (Connection connection = privileged.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            int index = 2;
            for (Object value : columns.values()) {
                statement.setObject(index++, value);
            }
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("seeding " + table + " for " + tenant + " failed", e);
        }
        return this;
    }

    /** Creates the tenant registry table (feature 50) and registers the given tenants. */
    public TenantPostgres withRegistry(String... activeTenants) {
        execute(io.tenantlayer.registry.TenantRegistrySchema
                .ddlFor(io.tenantlayer.registry.TenantRegistrySchema.DEFAULT_TABLE)
                + "grant select, insert, update, delete on "
                + io.tenantlayer.registry.TenantRegistrySchema.DEFAULT_TABLE
                + " to " + APP_USER + ";");
        var registry = new io.tenantlayer.registry.JdbcTenantRegistry(privileged);
        for (String tenant : activeTenants) {
            registry.save(io.tenantlayer.registry.TenantRegistration.of(tenant));
        }
        return this;
    }

    /**
     * The DataSource the code under test should use: least-privileged, and already wrapped
     * so the current tenant is published onto every connection.
     *
     * @param maxPoolSize 1 makes connection reuse deterministic, which is what a test for
     *                    tenant leakage across pooled connections needs
     */
    public DataSource applicationDataSource(int maxPoolSize) {
        return new TenantAwareDataSource(rawApplicationPool(maxPoolSize));
    }

    public DataSource applicationDataSource() {
        return applicationDataSource(1);
    }

    /** The same pool without the TenantLayer wrapper, for testing the library itself. */
    public synchronized HikariDataSource rawApplicationPool(int maxPoolSize) {
        return applicationPools.computeIfAbsent("app-" + maxPoolSize,
                name -> pool(name, APP_USER, APP_PASSWORD, maxPoolSize));
    }

    /** Bypasses every policy. For seeding and for proving other tenants' rows exist. */
    public DataSource privilegedDataSource() {
        return privileged;
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public String applicationUsername() {
        return APP_USER;
    }

    public String applicationPassword() {
        return APP_PASSWORD;
    }

    /** Runs arbitrary SQL as the privileged user — migrations, generated policies, cleanup. */
    public void execute(String sql) {
        try (Connection connection = privileged.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("fixture SQL failed:\n" + sql, e);
        }
    }

    public void truncate() {
        for (String table : tenantTables) {
            execute("truncate table " + table + " restart identity cascade");
        }
    }

    private HikariDataSource pool(String name, String user, String password, int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName(name);
        config.setMaximumPoolSize(size);
        return new HikariDataSource(config);
    }
}
