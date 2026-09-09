package io.tenantlayer.registry;

/**
 * Onboarding did not complete. The tenant is left {@link TenantStatus#PROVISIONING}.
 *
 * <p>The tenant is not usable and not served, which is the point: the alternative to a
 * clear failure is a tenant that looks ready and is not.
 */
public class TenantProvisioningException extends RuntimeException {

    private final String tenantId;
    private final String stage;

    public TenantProvisioningException(String tenantId, String stage, Throwable cause) {
        super("provisioning tenant '" + tenantId + "' failed at " + stage
                + "; it is left PROVISIONING and will not be served. "
                + "Fix the cause and onboard it again — onboarding is idempotent.", cause);
        this.tenantId = tenantId;
        this.stage = stage;
    }

    public String tenantId() {
        return tenantId;
    }

    /** Which part failed: "migration", or the name of a hook. */
    public String stage() {
        return stage;
    }
}
