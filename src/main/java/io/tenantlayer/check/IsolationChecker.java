package io.tenantlayer.check;

import io.tenantlayer.check.IsolationFinding.Severity;
import io.tenantlayer.schema.TenantScopedEntityScanner;
import io.tenantlayer.schema.TenantScopedTable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Feature 31 — compares what should be protected against what the database actually
 * enforces, and says so at start-up.
 *
 * <h2>Why this exists</h2>
 *
 * The entity scanner already knows which tables are tenant-scoped. Postgres already knows
 * which of them have row-level security and a policy. Nothing has ever compared the two, so
 * a table added last month without a policy looks exactly like one with a policy — until a
 * tenant reads another tenant's rows.
 *
 * <p>It is also the only way to find out, before switching a running application to a
 * least-privileged role, whether every table is actually covered.
 *
 * <h2>It warns and never fails</h2>
 *
 * An application that will not start is worse than one with a gap it has told you about,
 * and a checker that can halt a deployment is a checker people disable. Findings are logged;
 * the application starts either way. Callers wanting to fail a build can read
 * {@link #check()} themselves and decide.
 */
public class IsolationChecker {

    /** Tables Postgres reports as having row-level security, and whether it is forced. */
    private static final String RLS_STATUS = """
            select c.relname,
                   c.relrowsecurity   as enabled,
                   c.relforcerowsecurity as forced,
                   pg_get_userbyid(c.relowner) = current_user as owned_by_us
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where c.relkind = 'r'
               and n.nspname = current_schema()
               and c.relname = any (?)
            """;

    private static final String POLICIES =
            "select tablename from pg_policies where schemaname = current_schema() and tablename = any (?)";

    /** A superuser bypasses every policy, which makes all of the above decorative. */
    private static final String IS_SUPERUSER =
            "select coalesce(bool_or(usesuper), false) from pg_user where usename = current_user";

    private final TenantScopedEntityScanner scanner;
    private final DataSource dataSource;

    public IsolationChecker(TenantScopedEntityScanner scanner, DataSource dataSource) {
        this.scanner = scanner;
        this.dataSource = dataSource;
    }

    /**
     * @return every finding, worst first. Empty means the database enforces what the
     *         entities say it should.
     */
    public List<IsolationFinding> check() {
        List<TenantScopedTable> expected = scanner.scan();
        if (expected.isEmpty()) {
            return List.of();
        }

        List<IsolationFinding> findings = new ArrayList<>();
        String[] tableNames = expected.stream().map(TenantScopedTable::tableName).toArray(String[]::new);

        try (Connection connection = dataSource.getConnection()) {
            if (isSuperuser(connection)) {
                findings.add(new IsolationFinding(
                        Severity.NOT_ENFORCED,
                        "connection role",
                        "the application connects as a superuser, which bypasses every row-level "
                                + "security policy — no policy below is being applied",
                        "connect as a least-privileged role that does not own these tables"));
            }
            findings.addAll(examineTables(connection, expected, tableNames));
        } catch (SQLException e) {
            /* A checker that breaks start-up because it could not run is worse than one
               that quietly does not run. Report it as a finding and carry on. */
            findings.add(new IsolationFinding(
                    Severity.NOTE,
                    "isolation check",
                    "could not be completed: " + e.getMessage(),
                    "verify the application's role can read pg_class and pg_policies"));
        }
        findings.sort((a, b) -> a.severity().compareTo(b.severity()));
        return findings;
    }

    private boolean isSuperuser(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(IS_SUPERUSER);
                ResultSet rs = statement.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private List<IsolationFinding> examineTables(
            Connection connection, List<TenantScopedTable> expected, String[] tableNames)
            throws SQLException {

        Set<String> withPolicy = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(POLICIES)) {
            statement.setArray(1, connection.createArrayOf("text", tableNames));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    withPolicy.add(rs.getString(1));
                }
            }
        }

        List<IsolationFinding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try (PreparedStatement statement = connection.prepareStatement(RLS_STATUS)) {
            statement.setArray(1, connection.createArrayOf("text", tableNames));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("relname");
                    seen.add(table);
                    findings.addAll(examineOne(
                            table,
                            rs.getBoolean("enabled"),
                            rs.getBoolean("forced"),
                            rs.getBoolean("owned_by_us"),
                            withPolicy.contains(table)));
                }
            }
        }

        /* An entity mapped to a table that is not in this schema is not a policy problem,
           but it does mean the check said nothing about it — which is worth knowing. */
        for (TenantScopedTable table : expected) {
            if (!seen.contains(table.tableName())) {
                findings.add(new IsolationFinding(
                        Severity.NOTE,
                        table.tableName(),
                        "mapped by entity " + table.entityName()
                                + " but not found in the current schema, so it was not checked",
                        "confirm the table exists and the search_path reaches it"));
            }
        }
        return findings;
    }

    private List<IsolationFinding> examineOne(
            String table, boolean enabled, boolean forced, boolean ownedByUs, boolean hasPolicy) {

        List<IsolationFinding> findings = new ArrayList<>();

        if (!enabled) {
            findings.add(new IsolationFinding(
                    Severity.NOT_ENFORCED, table,
                    "is tenant-scoped but row-level security is not enabled, so every tenant "
                            + "can read every row",
                    "alter table " + table + " enable row level security;"));
            return findings;   // the rest is moot until it is on
        }

        if (!hasPolicy) {
            findings.add(new IsolationFinding(
                    Severity.NOT_ENFORCED, table,
                    "has row-level security enabled but no policy, so it returns nothing to "
                            + "anyone — which usually reads as a bug in the application",
                    "create a policy comparing the tenant column to "
                            + "nullif(current_setting('tenantlayer.tenant', true), '')"));
        }

        if (ownedByUs && !forced) {
            findings.add(new IsolationFinding(
                    Severity.NOT_ENFORCED, table,
                    "is owned by the connecting role and does not have FORCE ROW LEVEL "
                            + "SECURITY, so the policy is skipped for this application",
                    "alter table " + table + " force row level security;"));
        } else if (!forced) {
            findings.add(new IsolationFinding(
                    Severity.FRAGILE, table,
                    "does not have FORCE ROW LEVEL SECURITY. It is enforced today only because "
                            + "the connecting role does not own the table",
                    "alter table " + table + " force row level security;"));
        }

        return findings;
    }
}
