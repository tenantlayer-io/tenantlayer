package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.schema.RlsPolicyGenerator;
import io.tenantlayer.schema.TenantScopedEntityScanner;
import io.tenantlayer.schema.TenantScopedTable;
import io.tenantlayer.support.PostgresSupport;
import io.tenantlayer.support.TenantLayerTestBase;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Features 21 and 30 — find the tenant-scoped tables, then write the policies for them.
 *
 * {@link #generatedPolicyActuallyIsolates()} is the test that matters. Asserting that
 * generated SQL <em>contains</em> the right keywords proves only that the generator can
 * produce a convincing string. This one starts from a table with no policy, shows the
 * tenant reading every row on it, applies the generator's own output, and shows the same
 * query returning only that tenant's rows.
 */
class SchemaGenerationTest extends TenantLayerTestBase {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void reseed() {
        PostgresSupport.resetSchema();
    }

    private TenantScopedEntityScanner scanner() {
        return new TenantScopedEntityScanner(entityManagerFactory);
    }

    @Test
    @DisplayName("the scanner finds every table with a tenant column")
    void scannerFindsTenantScopedTables() {
        List<TenantScopedTable> found = scanner().scan();

        assertThat(found).extracting(TenantScopedTable::tableName)
                .contains("documents", "notes", "invoices");
        assertThat(found).extracting(TenantScopedTable::tenantColumn)
                .containsOnly("tenant_id");
    }

    @Test
    @DisplayName("an entity with no tenant column is not reported")
    void scannerIgnoresUnscopedEntities() {
        assertThat(scanner().scan()).extracting(TenantScopedTable::tableName)
                .as("the registry table is not a JPA entity and must not appear")
                .doesNotContain("tenantlayer_tenants");
    }

    @Test
    @DisplayName("excludes win over the column convention")
    void excludesWin() {
        var withExclusion = new TenantScopedEntityScanner(entityManagerFactory,
                "tenant_id", Set.of(), Set.of("notes"));

        assertThat(withExclusion.scan()).extracting(TenantScopedTable::tableName)
                .as("a shared table that happens to carry a tenant_id must be excludable")
                .doesNotContain("notes")
                .contains("documents");
    }

    @Test
    @DisplayName("the generated SQL carries the three things hand-written policies forget")
    void generatedSqlCarriesTheEasyMistakes() {
        String sql = new RlsPolicyGenerator().generateFor(
                new TenantScopedTable("Invoice", "invoices", "tenant_id"));

        assertThat(sql)
                .as("without FORCE, the table owner bypasses the policy entirely")
                .contains("alter table invoices force row level security")
                .as("after a reset current_setting returns '' rather than NULL")
                .contains("nullif(current_setting('tenantlayer.tenant', true), '')")
                .as("an unindexed policy predicate turns every read into a sequential scan")
                .contains("create index if not exists idx_invoices_tenant_id");
    }

    @Test
    @DisplayName("the generated policy actually isolates when applied")
    void generatedPolicyActuallyIsolates() {
        long beforeAsAcme = TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> countInvoices());

        assertThat(beforeAsAcme)
                .as("invoices starts with no policy, so acme must see all three rows — "
                    + "otherwise this test would pass without the generated SQL doing anything")
                .isEqualTo(3);

        apply(new RlsPolicyGenerator().generateFor(
                new TenantScopedTable("Invoice", "invoices", "tenant_id")));

        long afterAsAcme = TenantContext.callWithTenant(TenantScope.of("acme"),
                () -> countInvoices());
        long afterAsGlobex = TenantContext.callWithTenant(TenantScope.of("globex"),
                () -> countInvoices());
        long afterWithNoTenant = countInvoices();

        assertThat(afterAsAcme).as("acme owns exactly one invoice").isEqualTo(1);
        assertThat(afterAsGlobex).as("globex owns exactly two").isEqualTo(2);
        assertThat(afterWithNoTenant).as("no tenant must mean no rows, not all rows").isZero();
    }

    @Test
    @DisplayName("generating for the whole scan covers every discovered table")
    void generatesForEveryScannedTable() {
        String sql = new RlsPolicyGenerator().generate(scanner().scan());

        assertThat(sql)
                .contains("create policy documents_tenant_isolation")
                .contains("create policy notes_tenant_isolation")
                .contains("create policy invoices_tenant_isolation");
    }

    @Test
    @DisplayName("an empty scan produces a comment, not broken SQL")
    void emptyScanIsSafe() {
        String sql = new RlsPolicyGenerator().generate(List.of());

        assertThat(sql).contains("No tenant-scoped tables were found")
                .doesNotContain("create policy");
    }

    private long countInvoices() {
        try (Connection connection = applicationDataSource.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("select count(*) from invoices");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Applied on the privileged connection, the way a migration tool would. */
    private void apply(String sql) {
        try (Connection connection = PostgresSupport.privileged().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("generated SQL did not execute:\n" + sql, e);
        }
    }
}
