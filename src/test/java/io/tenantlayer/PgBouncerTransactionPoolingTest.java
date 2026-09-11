package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.strategy.TransactionScopedRowLevelSecurityStrategy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Native proof of the transaction-scoped strategy through real PgBouncer transaction pooling.
 *
 * <p>The Postgres role is deliberately {@code NOSUPERUSER NOBYPASSRLS}; the assertions therefore
 * prove policy enforcement rather than merely observing rows through an administrative role.
 * The control case uses session-scoped {@code set_config(..., false)} and must leak when the
 * same PgBouncer server backend is reused. The strategy under test uses {@code true} and must
 * preserve isolation across the same reuse.
 */
class PgBouncerTransactionPoolingTest {

    private static final String DATABASE = "tenantlayer";
    private static final String ADMIN_USER = "postgres_admin";
    private static final String ADMIN_PASSWORD = "admin_pwd";
    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_pwd";
    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName(DATABASE)
            .withUsername(ADMIN_USER)
            .withPassword(ADMIN_PASSWORD)
            .withNetwork(NETWORK)
            .withNetworkAliases("tenantlayer-postgres");

    private static final GenericContainer<?> PGBOUNCER =
            new GenericContainer<>("edoburu/pgbouncer:v1.25.2-p0")
                    .withNetwork(NETWORK)
                    .withExposedPorts(5432)
                    .withEnv("DB_HOST", "tenantlayer-postgres")
                    .withEnv("DB_PORT", "5432")
                    .withEnv("DB_NAME", DATABASE)
                    .withEnv("DB_USER", APP_USER)
                    .withEnv("DB_PASSWORD", APP_PASSWORD)
                    // PostgreSQL 16 stores this test role with a SCRAM verifier. The image
                    // defaults to md5 and generates an incompatible MD5 userlist entry; plain
                    // lets PgBouncer forward the password so PostgreSQL performs SCRAM.
                    .withEnv("AUTH_TYPE", "plain")
                    .withEnv("POOL_MODE", "transaction")
                    .withEnv("DEFAULT_POOL_SIZE", "1")
                    .withEnv("MAX_CLIENT_CONN", "20")
                    // Keep the deliberately unsafe control state observable. SET LOCAL does not
                    // depend on this setting and is still reverted by PostgreSQL itself.
                    .withEnv("SERVER_RESET_QUERY", "select 1")
                    .waitingFor(Wait.forListeningPort());

    private static HikariDataSource controlPool;
    private static HikariDataSource strategyPool;
    private static DataSource strategyDataSource;
    private static TransactionTemplate strategyTransactions;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        initializeDatabase();
        PGBOUNCER.start();

        controlPool = pool("pgbouncer-control");
        strategyPool = pool("pgbouncer-strategy");
        strategyDataSource = new TenantAwareDataSource(
                strategyPool, new TransactionScopedRowLevelSecurityStrategy(strategyPool));
        strategyTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(strategyDataSource));
    }

    @AfterAll
    static void stop() {
        close(controlPool);
        close(strategyPool);
        PGBOUNCER.stop();
        POSTGRES.stop();
        NETWORK.close();
    }

    @Test
    @DisplayName("session-scoped control leaks over reused PgBouncer backend")
    void unsafeSessionScopedControlLeaks() {
        TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                controlTransaction(connection -> {
                    setSessionTenant(connection, "acme");
                    assertThat(count(connection)).isEqualTo(2);
                }));

        TenantContext.runWithTenant(TenantScope.of("globex"), () ->
                controlTransaction(connection -> {
                    assertThat(backendPid(connection)).isEqualTo(controlBackendPid());
                    assertThat(count(connection))
                            .as("the unsafe session setting must be inherited by globex")
                            .isEqualTo(2);
                }));
    }

    @Test
    @DisplayName("transaction-scoped binding isolates tenants across reused PgBouncer backend")
    void transactionScopedBindingPreventsLeak() {
        Sample acme = inTransaction("acme", this::sample);
        Sample globex = inTransaction("globex", this::sample);

        assertThat(acme.rows()).isEqualTo(2);
        assertThat(globex.rows()).isEqualTo(3);
        assertThat(acme.backendPid()).isEqualTo(globex.backendPid());

        Integer acmeForeignRows = inTransaction("acme", c -> countOwnedBy(c, "globex"));
        Integer globexForeignRows = inTransaction("globex", c -> countOwnedBy(c, "acme"));
        assertThat(acmeForeignRows).isZero();
        assertThat(globexForeignRows).isZero();
        assertThat(outsideTransactionSetting()).isEmpty();
    }

    @Test
    @DisplayName("rollback also clears transaction-local state before reuse")
    void rollbackClearsLocalState() {
        try {
            TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                    strategyTransactions.executeWithoutResult(status -> {
                        Connection connection = DataSourceUtils.getConnection(strategyDataSource);
                        assertThat(count(connection)).isEqualTo(2);
                        throw new IllegalStateException("force rollback");
                    }));
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("force rollback");
        }

        assertThat(outsideTransactionSetting()).isEmpty();
        Integer visible = inTransaction("globex", c -> count(c));
        assertThat(visible).isEqualTo(3);
    }

    private static void initializeDatabase() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), ADMIN_USER, ADMIN_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("drop role if exists " + APP_USER);
            statement.execute("create role " + APP_USER
                    + " login password '" + APP_PASSWORD
                    + "' nosuperuser nocreatedb nocreaterole nobypassrls");
            statement.execute("create table documents (id bigint primary key, tenant_id text not null, body text not null)");
            statement.execute("insert into documents values"
                    + " (1, 'acme', 'a1'), (2, 'acme', 'a2'),"
                    + " (3, 'globex', 'g1'), (4, 'globex', 'g2'), (5, 'globex', 'g3')");
            statement.execute("alter table documents enable row level security");
            statement.execute("alter table documents force row level security");
            statement.execute("create policy tenant_isolation on documents using"
                    + " (tenant_id = nullif(current_setting('tenantlayer.tenant', true), ''))");
            statement.execute("grant usage on schema public to " + APP_USER);
            statement.execute("grant select on documents to " + APP_USER);
        } catch (Exception e) {
            throw new IllegalStateException("could not initialize PgBouncer proof database", e);
        }
    }

    private static HikariDataSource pool(String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + PGBOUNCER.getHost() + ":"
                + PGBOUNCER.getMappedPort(5432) + "/" + DATABASE);
        config.setUsername(APP_USER);
        config.setPassword(APP_PASSWORD);
        config.setPoolName(name);
        // Some assertions inspect the backend PID while the control transaction still holds
        // its connection; keep a second client slot without changing PgBouncer's one-backend
        // transaction-pooling constraint.
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }

    private void controlTransaction(SqlWork work) {
        try (Connection connection = controlPool.getConnection()) {
            connection.setAutoCommit(false);
            work.run(connection);
            connection.commit();
        } catch (Exception e) {
            throw new IllegalStateException("control transaction failed", e);
        }
    }

    private Sample sample(Connection connection) {
        return new Sample(count(connection), backendPid(connection));
    }

    private <T> T inTransaction(String tenant, SqlValue<T> body) {
        return TenantContext.callWithTenant(TenantScope.of(tenant),
                () -> strategyTransactions.execute(status -> body.apply(
                        DataSourceUtils.getConnection(strategyDataSource))));
    }

    private int controlBackendPid() {
        try (Connection connection = controlPool.getConnection()) {
            return backendPid(connection);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String outsideTransactionSetting() {
        TenantContext.clear();
        try (Connection connection = strategyDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select current_setting('tenantlayer.tenant', true)")) {
            result.next();
            return result.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("could not inspect cleared tenant setting", e);
        }
    }

    private static void setSessionTenant(Connection connection, String tenant) throws Exception {
        try (var statement = connection.prepareStatement(
                "select set_config('tenantlayer.tenant', ?, false)")) {
            statement.setString(1, tenant);
            statement.execute();
        }
    }

    private static int count(Connection connection) {
        return queryInt(connection, "select count(*) from documents");
    }

    private static int countOwnedBy(Connection connection, String tenant) {
        try (var statement = connection.prepareStatement(
                "select count(*) from documents where tenant_id = ?")) {
            statement.setString(1, tenant);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int backendPid(Connection connection) {
        return queryInt(connection, "select pg_backend_pid()");
    }

    private static int queryInt(Connection connection, String sql) {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            throw new IllegalStateException("could not close PgBouncer proof resource", e);
        }
    }

    private record Sample(int rows, int backendPid) { }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }

    @FunctionalInterface
    private interface SqlValue<T> {
        T apply(Connection connection);
    }
}
