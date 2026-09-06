package io.tenantlayer.migration;

/** Migrations failed for at least one tenant. Names them, because "migrations failed" is not actionable. */
public class TenantMigrationException extends RuntimeException {

    private final transient MigrationOutcome outcome;

    public TenantMigrationException(MigrationOutcome outcome, int attempted) {
        super(outcome.failures().size() + " of " + attempted + " tenants failed to migrate: "
              + String.join(", ", outcome.failedTenants()));
        this.outcome = outcome;
        outcome.failures().values().stream().findFirst().ifPresent(this::initCause);
    }

    public MigrationOutcome outcome() {
        return outcome;
    }
}
