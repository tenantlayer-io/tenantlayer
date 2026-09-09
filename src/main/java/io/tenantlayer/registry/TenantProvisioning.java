package io.tenantlayer.registry;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import io.tenantlayer.migration.TenantMigrationRunner;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature 67 — turns a tenant id into a tenant that works.
 *
 * <h2>What onboarding actually is</h2>
 *
 * Three things, in an order that matters: the tenant has to exist before anything can be
 * done for it, its storage has to exist before anything can be written to it, and only then
 * is it safe to serve. Doing them in any other order produces a tenant that is briefly
 * live and broken.
 *
 * <pre>
 *   registry row, PROVISIONING   the tenant exists; nothing serves it yet
 *   migrate                      its schema or database, when the strategy needs one
 *   hooks                        seed data and everything application-specific
 *   registry row, ACTIVE         now it is served
 * </pre>
 *
 * <h2>This is a runtime call, not a deployment</h2>
 *
 * A tenant that signs up at three in the morning is a method call from your own signup
 * path. Nothing here happens at start-up, and no release is involved.
 *
 * <h2>What it does not do</h2>
 *
 * It provisions <em>this service</em>. In an estate of twenty services, exactly one should
 * own the registry row — whichever service owns signing up — and the others provision their
 * own storage when they learn of the tenant, by event or at their next deployment via
 * {@code migrateAll()}. A library cannot reach twenty services, and pretending otherwise
 * would produce an API that lies. Under row-level security the question does not arise:
 * the tables are shared and nineteen of them have nothing to do.
 */
public class TenantProvisioning {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioning.class);

    private final TenantRegistry registry;
    private final TenantMigrationRunner migrations;
    private final List<TenantProvisioningHook> hooks;

    public TenantProvisioning(TenantRegistry registry,
                              TenantMigrationRunner migrations,
                              List<TenantProvisioningHook> hooks) {
        this.registry = registry;
        this.migrations = migrations;
        this.hooks = hooks == null
                ? List.of()
                : hooks.stream().sorted(Comparator.comparingInt(TenantProvisioningHook::order)).toList();
    }

    /** Onboards a tenant with no metadata beyond its id. */
    public TenantRegistration onboard(String tenantId) {
        return onboard(TenantRegistration.of(tenantId));
    }

    /**
     * Onboards a tenant, using the given registration for region, group, datasource
     * reference and metadata.
     *
     * <p><strong>Idempotent.</strong> A tenant that is already ACTIVE is returned untouched,
     * so a retried webhook, a redelivered message and an operator running this twice are all
     * safe. A tenant left PROVISIONING by an earlier failure is resumed from the beginning,
     * which is why hooks must tolerate running again.
     *
     * @throws TenantProvisioningException if migration or a hook fails; the tenant is left
     *                                     PROVISIONING and is not served
     */
    public TenantRegistration onboard(TenantRegistration desired) {
        String tenantId = desired.tenantId();

        Optional<TenantRegistration> existing = registry.find(tenantId);
        if (existing.isPresent() && existing.get().status() == TenantStatus.ACTIVE) {
            log.debug("tenant '{}' is already active; onboarding is a no-op", tenantId);
            return existing.get();
        }

        registry.save(withStatus(desired, TenantStatus.PROVISIONING));

        try {
            migrate(tenantId);
        } catch (RuntimeException e) {
            throw new TenantProvisioningException(tenantId, "migration", e);
        }

        for (TenantProvisioningHook hook : hooks) {
            try {
                /* Bound while the hook runs, so an implementation writes seed data with
                   ordinary repository calls. Without this the rows fail the policy under
                   row-level security, and have no connection at all under
                   database-per-tenant — the mistake everyone makes writing this by hand. */
                TenantContext.runWithTenant(TenantScope.of(tenantId), () -> hook.onTenantCreated(tenantId));
            } catch (RuntimeException e) {
                throw new TenantProvisioningException(tenantId, "hook " + hook.name(), e);
            }
        }

        TenantRegistration active = withStatus(desired, TenantStatus.ACTIVE);
        registry.save(active);
        log.info("tenant '{}' provisioned: {} hook(s) ran", tenantId, hooks.size());
        return active;
    }

    /**
     * Only when the strategy gives each tenant its own store. Under row-level security the
     * tables are shared and already migrated, so running Flyway would be a round trip that
     * applies nothing.
     */
    private void migrate(String tenantId) {
        if (migrations == null) {
            return;
        }
        if (!migrations.migratesPerTenant()) {
            log.debug("strategy shares one store; tenant '{}' needs no migration of its own", tenantId);
            return;
        }
        migrations.migrate(tenantId);
    }

    private static TenantRegistration withStatus(TenantRegistration source, TenantStatus status) {
        return new TenantRegistration(
                source.tenantId(), status, source.region(), source.group(),
                source.datasourceRef(),
                source.metadata() == null ? Map.of() : source.metadata());
    }
}
