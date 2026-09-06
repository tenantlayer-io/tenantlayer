package io.tenantlayer.strategy;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Feature 24 — how a connection is obtained and prepared for the acting tenant.
 *
 * <h2>Why the seam is here and not one level up</h2>
 *
 * Row-level security and schema-per-tenant both acquire a connection from a single pool and
 * then run one statement on it. Database-per-tenant does not: it has to choose a
 * <em>different</em> pool before acquiring anything. A hook that only prepared an
 * already-acquired connection would fit two of the three strategies and force the third to
 * be bolted on beside it.
 *
 * <p>So the strategy owns acquisition. Everything above it — resolution, the context, the
 * filter — is unchanged and unaware; by the time a strategy is called, the tenant is
 * already known.
 *
 * <h2>Fail-closed is not uniform, deliberately</h2>
 *
 * Each strategy fails closed differently when no tenant is bound: session-scoped row-level
 * security returns an empty result set, transaction-scoped row-level security rejects work
 * outside a transaction, schema-per-tenant raises an unresolved-relation error, and
 * database-per-tenant throws before a connection exists. All are safe — none leaks — but an
 * application that silently copes with empty results will fail loudly under the others.
 * Switching strategy is therefore <strong>not behaviour-preserving on the no-tenant or
 * no-transaction paths</strong>, which is a property of the switch rather than a bug in it.
 *
 * <h2>Adding to this interface</h2>
 *
 * Users implement this. Any method added later must be {@code default}, or every existing
 * implementation stops compiling.
 */
public interface TenantConnectionStrategy {

    /** A connection prepared for whatever tenant is currently bound, or for none. */
    Connection getConnection() throws SQLException;

    Connection getConnection(String username, String password) throws SQLException;

    /** Short identifier, for diagnostics and for the isolation checker. */
    String name();

    /**
     * Whether this strategy expects row-level security policies to exist on tenant-scoped
     * tables. False for database-per-tenant, where the pool is the isolation and a policy
     * would be meaningless.
     *
     * <p>The RLS policy generator and the startup checker read this so they know when to
     * stay quiet.
     */
    default boolean expectsRowLevelSecurity() {
        return true;
    }

    /**
     * The schema a tenant's tables live in, when the strategy gives each tenant its own.
     *
     * <p>Empty means every tenant shares one schema — which is what row-level security
     * does — and is the reason this returns an {@link java.util.Optional} rather than a
     * name. A migration runner needs to tell those apart: under a shared schema there is
     * one set of tables and migrating "per tenant" would run the same migrations
     * repeatedly against the same tables.
     *
     * @return the schema, or empty when tenants share one
     */
    default java.util.Optional<String> schemaFor(String tenantId) {
        return java.util.Optional.empty();
    }
}
