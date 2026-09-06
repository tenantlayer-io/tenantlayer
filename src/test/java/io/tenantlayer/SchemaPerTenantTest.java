package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.core.TenantAwareDataSource;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.strategy.SchemaPerTenantStrategy;
import io.tenantlayer.support.PostgresSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature 22 — schema-per-tenant.
 *
 * These tables have <strong>no row-level security policy</strong>, deliberately. Any
 * isolation observed here is the search_path doing the work. If the strategy stops setting
 * it, acme reads globex's rows — there is nothing downstream to catch it, which is exactly
 * why the classic search_path leak is worse than the GUC one.
 */
class SchemaPerTenantTest {

    private static DataSource schemaScoped;

    @BeforeAll
    static void prepareSchemas() {
        PostgresSupport.start();
        // Two schemas, same table name, different rows, no policies anywhere.
        PostgresSupport.executeAsAdmin("""
                drop schema if exists tenant_acme cascade;
                drop schema if exists tenant_globex cascade;
                create schema tenant_acme;
                create schema tenant_globex;

                create table tenant_acme.notes  (id bigserial primary key, body varchar(255));
                create table tenant_globex.notes (id bigserial primary key, body varchar(255));

                insert into tenant_acme.notes (body)  values ('acme one'), ('acme two');
                insert into tenant_globex.notes (body) values ('globex one');

                grant usage on schema tenant_acme, tenant_globex to app_user;
                grant select, insert, update, delete on all tables in schema tenant_acme  to app_user;
                grant select, insert, update, delete on all tables in schema tenant_globex to app_user;
                """);

        DataSource pool = PostgresSupport.applicationPool(1);
        schemaScoped = new TenantAwareDataSource(pool,
                new SchemaPerTenantStrategy(pool, "tenant_"));
    }

    private long countNotes() throws SQLException {
        try (Connection connection = schemaScoped.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select count(*) from notes")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("each tenant reads its own schema, on a shared pool with no policies")
    void eachTenantReadsItsOwnSchema() {
        long acme = TenantContext.callWithTenant(TenantScope.of("acme"), this::countNotes);
        long globex = TenantContext.callWithTenant(TenantScope.of("globex"), this::countNotes);

        assertThat(acme)
                .as("acme's schema has two rows; identical counts would mean routing did nothing")
                .isEqualTo(2);
        assertThat(globex).isEqualTo(1);
    }

    @Test
    @DisplayName("a recycled connection does not inherit the previous tenant's schema")
    void recycledConnectionDoesNotInheritSchema() throws SQLException {
        // The pool holds one connection, so the second call provably reuses the first's.
        long asAcme = TenantContext.callWithTenant(TenantScope.of("acme"), this::countNotes);
        assertThat(asAcme).as("otherwise the assertion below is vacuous").isEqualTo(2);

        assertThatThrownBy(this::countNotes)
                .as("with no tenant the search_path is empty, so nothing resolves — it must "
                    + "not silently keep acme's schema, and must not fall back to public")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("notes");
    }

    @Test
    @DisplayName("a tenant id that cannot be a schema name is refused, not interpolated")
    void unsafeTenantIdentifierIsRefused() {
        SchemaPerTenantStrategy strategy =
                new SchemaPerTenantStrategy(PostgresSupport.applicationPool(1), "tenant_");

        assertThatThrownBy(() -> strategy.schemaNameFor("acme; drop schema tenant_globex cascade"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be used as a schema name");
        assertThat(strategy.schemaNameFor("acme")).isEqualTo("tenant_acme");
    }

    @Test
    @DisplayName("this strategy expects no row-level security policies")
    void doesNotExpectRowLevelSecurity() {
        assertThat(new SchemaPerTenantStrategy(PostgresSupport.applicationPool(1), "tenant_")
                .expectsRowLevelSecurity())
                .as("the policy generator and the checker read this to know to stay quiet")
                .isFalse();
    }
}
