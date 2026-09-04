package io.tenantlayer;

import static io.tenantlayer.test.IsolationAssertions.assertTenantCannotSee;
import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.test.IsolationAssertions;
import io.tenantlayer.test.TenantPostgres;
import io.tenantlayer.test.WithTenant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature 105 — written the way a user of the library would write it.
 *
 * This test deliberately uses nothing from {@code io.tenantlayer.support}: no shared
 * PostgresSupport, no TenantLayerTestBase, no Spring context. Everything it touches is
 * shipped in the published jar. If the fixture is not genuinely usable from outside, this
 * file does not compile — which is a stronger guarantee than a fixture that only works
 * because the library's own test tree happens to be on the classpath.
 */
class ShippedFixtureTest {

    private static TenantPostgres postgres;
    private static DataSource application;

    @BeforeAll
    static void startDatabase() {
        postgres = TenantPostgres.start()
                .withTenantTable("reports", "title varchar(255) not null");
        application = postgres.applicationDataSource(1);
    }

    @BeforeEach
    void seed() {
        postgres.execute("truncate table reports restart identity");
        postgres.seedRow("reports", "acme", Map.of("title", "acme q3"));
        postgres.seedRow("reports", "acme", Map.of("title", "acme q4"));
        postgres.seedRow("reports", "globex", Map.of("title", "globex q3"));

        IsolationAssertions.bind(application, postgres.privilegedDataSource());
        IsolationAssertions.bindTable("reports", "tenant_id");
    }

    /**
     * Cleared after, not before. JUnit runs a BeforeEachCallback extension — which is how
     * {@code @WithTenant} binds the tenant — ahead of @BeforeEach methods, so clearing
     * here would erase the tenant the annotation had just set.
     */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @WithTenant("acme")
    @DisplayName("the fixture's application connection is subject to the policy")
    void applicationConnectionIsSubjectToThePolicy() {
        assertThat(count(application))
                .as("acme's own rows must be visible, or the isolation check below is vacuous")
                .isEqualTo(2);

        assertTenantCannotSee("globex");
    }

    @Test
    @DisplayName("the privileged connection deliberately bypasses the policy")
    void privilegedConnectionBypassesThePolicy() {
        assertThat(count(postgres.privilegedDataSource()))
                .as("seeding and existence checks need a connection the policy does not apply to")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the application role is not a superuser — the mistake this fixture prevents")
    void applicationRoleIsNotSuperuser() {
        assertThat(isSuperuser(application))
                .as("a superuser bypasses RLS outright, so every isolation test would pass "
                    + "whether or not the policy worked")
                .isFalse();
        assertThat(isSuperuser(postgres.privilegedDataSource())).isTrue();
    }

    @Test
    @DisplayName("no tenant means no rows, on a connection returned to the pool")
    void noTenantMeansNoRows() {
        long asAcme = TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> count(application));
        assertThat(asAcme).isEqualTo(2);

        assertThat(count(application))
                .as("the pool has one connection, so a leaked tenant would be observed here")
                .isZero();
    }

    @Test
    @DisplayName("an unprotected table is available for discriminator-strategy tests")
    void unprotectedTableIsAvailable() {
        postgres.withUnprotectedTable("memos", "body varchar(255) not null");
        postgres.seedRow("memos", "acme", Map.of("body", "acme memo"));
        postgres.seedRow("memos", "globex", Map.of("body", "globex memo"));

        long visible = TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> countFrom(application, "memos"));

        assertThat(visible)
                .as("no policy here on purpose: this table is for proving @TenantId works")
                .isEqualTo(2);
    }

    private long count(DataSource dataSource) {
        return countFrom(dataSource, "reports");
    }

    private long countFrom(DataSource dataSource, String table) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("select count(*) from " + table);
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isSuperuser(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select usesuper from pg_user where usename = current_user");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
