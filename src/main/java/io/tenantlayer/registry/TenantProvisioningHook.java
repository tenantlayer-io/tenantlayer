package io.tenantlayer.registry;

/**
 * Feature 53 — what else a new tenant needs before it is usable.
 *
 * <h2>Why this is an interface</h2>
 *
 * Creating a registry row is not the same as a usable tenant. Seed data, a default
 * workspace, a Stripe customer, a search index, a warmed cache — all of it is
 * application-specific, none of it belongs in a library, and every application needs
 * somewhere to put it that runs at the right moment.
 *
 * <h2>The tenant is bound while these run</h2>
 *
 * Each hook is invoked with the new tenant bound to the context, so an implementation
 * writes seed data with ordinary repository calls and no tenant parameter. That is
 * deliberate: rows written with no tenant bound fail the policy under row-level security
 * and have no connection at all under database-per-tenant, and it is the mistake everyone
 * makes writing this by hand.
 *
 * <h2>Throwing is meaningful</h2>
 *
 * A hook that throws stops provisioning. The tenant is left
 * {@link TenantStatus#PROVISIONING}, which is not served and not iterated, and the failure
 * names the hook. Onboarding is retried by calling it again — so a hook should be safe to
 * run twice, because it will be.
 */
public interface TenantProvisioningHook {

    /**
     * @param tenantId the tenant being created, already bound to the context
     */
    void onTenantCreated(String tenantId);

    /**
     * Lower runs first. Hooks that others depend on — a schema, a default workspace —
     * should order themselves before the hooks that need them.
     */
    default int order() {
        return 0;
    }

    /** For diagnostics, so a failure names something a human recognises. */
    default String name() {
        return getClass().getSimpleName();
    }
}
