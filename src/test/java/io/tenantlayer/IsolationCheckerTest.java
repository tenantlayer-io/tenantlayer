package io.tenantlayer;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.check.IsolationChecker;
import io.tenantlayer.check.IsolationFinding;
import io.tenantlayer.check.IsolationFinding.Severity;
import io.tenantlayer.schema.TenantScopedEntityScanner;
import io.tenantlayer.schema.TenantScopedTable;
import io.tenantlayer.support.PostgresSupport;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature 31 — the checker, against a database deliberately set up four different ways.
 *
 * <p>The scanner is stubbed rather than driven from an EntityManagerFactory: what is under
 * test is the comparison against what Postgres actually enforces, not entity scanning,
 * which has its own tests.
 */
class IsolationCheckerTest {

    private static DataSource privileged;

    @BeforeAll
    static void prepareTables() {
        PostgresSupport.start();
        privileged = PostgresSupport.privileged();

        PostgresSupport.executeAsAdmin("""
                drop table if exists chk_correct, chk_no_rls, chk_no_policy, chk_not_forced cascade;

                -- 1. Correct: RLS on, forced, and a policy.
                create table chk_correct (id bigserial primary key, tenant_id varchar(64) not null);
                alter table chk_correct enable row level security;
                alter table chk_correct force row level security;
                create policy p on chk_correct
                    using (tenant_id = nullif(current_setting('tenantlayer.tenant', true), ''));

                -- 2. Someone added the table and forgot everything.
                create table chk_no_rls (id bigserial primary key, tenant_id varchar(64) not null);

                -- 3. RLS switched on, no policy — returns nothing to anyone.
                create table chk_no_policy (id bigserial primary key, tenant_id varchar(64) not null);
                alter table chk_no_policy enable row level security;

                -- 4. Policy present, FORCE missing — the owner slips past it.
                create table chk_not_forced (id bigserial primary key, tenant_id varchar(64) not null);
                alter table chk_not_forced enable row level security;
                create policy p on chk_not_forced
                    using (tenant_id = nullif(current_setting('tenantlayer.tenant', true), ''));
                """);
    }

    /** A scanner that reports whatever the test says should be tenant-scoped. */
    private static TenantScopedEntityScanner scannerFor(String... tables) {
        return new TenantScopedEntityScanner(null) {
            @Override
            public List<TenantScopedTable> scan() {
                return java.util.Arrays.stream(tables)
                        .map(t -> new TenantScopedTable("Entity_" + t, t, "tenant_id"))
                        .toList();
            }
        };
    }

    private static List<IsolationFinding> checkOf(String... tables) {
        return new IsolationChecker(scannerFor(tables), privileged).check();
    }

    private static List<IsolationFinding> forTable(List<IsolationFinding> findings, String table) {
        return findings.stream().filter(f -> f.subject().equals(table)).toList();
    }

    @Test
    @DisplayName("a table with no row-level security is reported as not enforced")
    void missingRowLevelSecurityIsReported() {
        List<IsolationFinding> findings = forTable(checkOf("chk_no_rls"), "chk_no_rls");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.NOT_ENFORCED);
        assertThat(findings.get(0).problem()).contains("row-level security is not enabled");
        assertThat(findings.get(0).fix()).contains("enable row level security");
    }

    @Test
    @DisplayName("row-level security with no policy is reported, because it returns nothing to anyone")
    void missingPolicyIsReported() {
        List<IsolationFinding> findings = forTable(checkOf("chk_no_policy"), "chk_no_policy");

        assertThat(findings).extracting(IsolationFinding::problem)
                .anyMatch(p -> p.contains("no policy"));
    }

    @Test
    @DisplayName("a missing FORCE is reported, since the owner would slip past the policy")
    void missingForceIsReported() {
        List<IsolationFinding> findings = forTable(checkOf("chk_not_forced"), "chk_not_forced");

        assertThat(findings).extracting(IsolationFinding::problem)
                .anyMatch(p -> p.contains("FORCE ROW LEVEL SECURITY"));
    }

    @Test
    @DisplayName("a correctly protected table produces no findings at all")
    void correctTableIsSilent() {
        assertThat(forTable(checkOf("chk_correct"), "chk_correct")).isEmpty();
    }

    @Test
    @DisplayName("connecting as a superuser is reported, because it makes every policy decorative")
    void superuserIsReported() {
        // PostgresSupport.privileged() is the container's superuser.
        assertThat(checkOf("chk_correct"))
                .extracting(IsolationFinding::subject)
                .contains("connection role");
    }

    @Test
    @DisplayName("an entity mapped to a table that does not exist is noted, not silently skipped")
    void unknownTableIsNoted() {
        List<IsolationFinding> findings = forTable(checkOf("chk_missing_entirely"), "chk_missing_entirely");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.NOTE);
        assertThat(findings.get(0).problem()).contains("not found in the current schema");
    }

    @Test
    @DisplayName("nothing tenant-scoped means nothing to say")
    void noEntitiesMeansNoFindings() {
        assertThat(checkOf()).isEmpty();
    }

    @Test
    @DisplayName("findings come back worst first")
    void findingsAreOrderedBySeverity() {
        List<Severity> severities = checkOf("chk_no_rls", "chk_correct", "chk_missing_entirely")
                .stream().map(IsolationFinding::severity).toList();

        assertThat(severities).isSorted();
    }
}
