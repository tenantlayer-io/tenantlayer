package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.core.NoTenantException;
import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.strategy.ConfiguredTenantDataSourceProvider;
import io.tenantlayer.strategy.DatabasePerTenantStrategy;
import io.tenantlayer.strategy.TenantDatabase;
import io.tenantlayer.strategy.UnknownTenantDatabaseException;
import io.tenantlayer.support.PostgresSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature 23 — database-per-tenant, against two real databases.
 *
 * <p>These tables have no row-level security policy and no schema separation. The two
 * tenants share a table name and a schema name; the only thing keeping them apart is which
 * database the connection was opened against. If the strategy stops choosing per tenant,
 * acme reads globex's rows and this test says so.
 */
class DatabasePerTenantTest {

    private static ConfiguredTenantDataSourceProvider provider;
    private static DataSource tenantScoped;

    @BeforeAll
    static void prepareDatabases() {
        PostgresSupport.start();
        PostgresSupport.createDatabase("db_acme");
        PostgresSupport.createDatabase("db_globex");

        seed("db_acme", "acme one", "acme two");
        seed("db_globex", "globex only");

        provider = new ConfiguredTenantDataSourceProvider(Map.of(
                "acme", new TenantDatabase(PostgresSupport.urlForDatabase("db_acme"),
                        PostgresSupport.adminUser(), PostgresSupport.adminPassword(), 2),
                "globex", new TenantDatabase(PostgresSupport.urlForDatabase("db_globex"),
                        PostgresSupport.adminUser(), PostgresSupport.adminPassword(), 2)));

        tenantScoped = new TenantAwareDataSource(
                PostgresSupport.privileged(), new DatabasePerTenantStrategy(provider));
    }

    @AfterAll
    static void closePools() {
        provider.shutdown();
    }

    private static void seed(String database, String... bodies) {
        try (var pool = PostgresSupport.privilegedPoolInDatabase(database);
                Connection c = pool.getConnection();
                Statement s = c.createStatement()) {
            s.execute("create table if not exists notes (id bigserial primary key, body varchar(255))");
            s.execute("truncate notes");
            for (String body : bodies) {
                s.execute("insert into notes (body) values ('" + body + "')");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed " + database, e);
        }
    }

    private static java.util.List<String> notesFor(String tenant) {
        return TenantContext.callWithTenant(TenantScope.of(tenant), () -> {
            var found = new java.util.ArrayList<String>();
            try (Connection c = tenantScoped.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("select body from notes order by id")) {
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
            }
            return found;
        });
    }

    @Test
    @DisplayName("each tenant reads only its own database")
    void eachTenantSeesOnlyItsOwnDatabase() {
        assertThat(notesFor("acme")).containsExactly("acme one", "acme two");
        assertThat(notesFor("globex")).containsExactly("globex only");
    }

    @Test
    @DisplayName("with no tenant bound, no connection is handed out at all")
    void noTenantMeansNoConnection() {
        TenantContext.clear();
        assertThatThrownBy(() -> tenantScoped.getConnection())
                .isInstanceOf(NoTenantException.class);
    }

    @Test
    @DisplayName("an unknown tenant fails closed rather than reaching a default database")
    void unknownTenantFailsClosed() {
        TenantContext.runWithTenant(TenantScope.of("stranger"), () ->
                assertThatThrownBy(() -> tenantScoped.getConnection())
                        .isInstanceOf(UnknownTenantDatabaseException.class)
                        .hasMessageContaining("stranger"));
    }

    @Test
    @DisplayName("pools are opened lazily, one per database actually used")
    void poolsAreLazy() throws SQLException {
        var fresh = new ConfiguredTenantDataSourceProvider(Map.of(
                "acme", new TenantDatabase(PostgresSupport.urlForDatabase("db_acme"),
                        PostgresSupport.adminUser(), PostgresSupport.adminPassword(), 1),
                "globex", new TenantDatabase(PostgresSupport.urlForDatabase("db_globex"),
                        PostgresSupport.adminUser(), PostgresSupport.adminPassword(), 1)));
        try {
            assertThat(fresh.openPools()).isZero();
            fresh.forTenant("acme").orElseThrow().getConnection().close();
            assertThat(fresh.openPools()).isEqualTo(1);
            fresh.forTenant("acme").orElseThrow().getConnection().close();
            assertThat(fresh.openPools()).as("asking twice must not open a second pool").isEqualTo(1);
        } finally {
            fresh.shutdown();
        }
    }
}
