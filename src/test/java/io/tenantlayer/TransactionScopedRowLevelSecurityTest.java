package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.strategy.TransactionScopedRowLevelSecurityStrategy;
import io.tenantlayer.support.PostgresSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Feature 29 — transaction-local RLS binding.
 *
 * <p>The application connection is the least-privileged role from {@link PostgresSupport}; the
 * privileged connection is used only to prove seed data exists. The transaction manager is the
 * same JDBC lifecycle Spring uses for {@code @Transactional}: it calls {@code setAutoCommit(false)}
 * before the first application statement and commits or rolls back afterwards.
 */
class TransactionScopedRowLevelSecurityTest {

    private static DataSource application;
    private static DataSource rawApplicationPool;
    private static TransactionTemplate transactions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        PostgresSupport.resetSchema();
        rawApplicationPool = PostgresSupport.applicationPool(1);
        application = new TenantAwareDataSource(rawApplicationPool,
                new TransactionScopedRowLevelSecurityStrategy(rawApplicationPool));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(application));
        jdbc = new JdbcTemplate(application);
    }

    @AfterAll
    static void stop() {
        if (rawApplicationPool instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("could not close transaction-scoped test pool", e);
            }
        }
    }

    @BeforeEach
    void reset() {
        PostgresSupport.resetSchema();
    }

    @Test
    @DisplayName("the tenant is bound when the Spring transaction begins")
    void bindsTenantInsideTransaction() {
        String setting = inTransaction("acme",
                () -> jdbc.queryForObject(
                        "select current_setting('tenantlayer.tenant', true)", String.class));

        assertThat(setting).isEqualTo("acme");
    }

    @Test
    @DisplayName("commit removes the local setting before the connection is reused")
    void commitRevertsTenantSetting() {
        long visible = inTransaction("acme", this::countDocuments);

        assertThat(visible).isEqualTo(2);
        assertThat(rawTenantSetting()).as("SET LOCAL must not survive commit").isEmpty();
        assertThat(inTransaction("globex", this::countDocuments))
                .as("the next transaction must bind its own tenant")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("rollback removes the local setting before the connection is reused")
    void rollbackRevertsTenantSetting() {
        assertThatThrownBy(() -> inTransaction("globex", () -> {
            assertThat(jdbc.queryForObject(
                    "select current_setting('tenantlayer.tenant', true)", String.class))
                    .isEqualTo("globex");
            throw new IllegalStateException("deliberate rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("deliberate rollback");

        assertThat(rawTenantSetting()).as("SET LOCAL must not survive rollback").isEmpty();
    }

    @Test
    @DisplayName("a statement outside a transaction fails closed")
    void outsideTransactionFailsClosed() {
        TenantContext.runWithTenant(TenantScope.of("acme"), () -> assertThatThrownBy(() -> {
            try (Connection connection = application.getConnection()) {
                connection.prepareStatement("select count(*) from documents");
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an active transaction"));
    }

    @Test
    @DisplayName("a tenant sees its own rows and cannot see another tenant's rows")
    void tenantIsolationUsesLeastPrivilegedConnection() {
        assertThat(countPrivileged("globex"))
                .as("the negative isolation assertion must not be vacuous")
                .isEqualTo(3);

        TenantContext.runWithTenant(TenantScope.of("acme"), () ->
                transactions.executeWithoutResult(status -> {
                    assertThat(countDocuments()).as("acme must see its own rows").isEqualTo(2);
                    assertThat(countOwnedBy("globex"))
                            .as("acme must not see globex rows through the app role")
                            .isZero();
                }));
    }

    private long countDocuments() {
        return jdbc.queryForObject("select count(*) from documents", Long.class);
    }

    private long countOwnedBy(String tenant) {
        return jdbc.queryForObject("select count(*) from documents where tenant_id = ?",
                Long.class, tenant);
    }

    private long countPrivileged(String tenant) {
        try (Connection connection = PostgresSupport.privileged().getConnection();
             var statement = connection.prepareStatement(
                     "select count(*) from documents where tenant_id = ?")) {
            statement.setString(1, tenant);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not count seeded rows", e);
        }
    }

    private String rawTenantSetting() {
        try (Connection connection = rawApplicationPool.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select current_setting('tenantlayer.tenant', true)")) {
            result.next();
            return result.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("could not inspect the returned pool connection", e);
        }
    }

    private <T> T inTransaction(String tenant, java.util.function.Supplier<T> work) {
        return TenantContext.callWithTenant(TenantScope.of(tenant),
                () -> transactions.execute(status -> work.get()));
    }
}
