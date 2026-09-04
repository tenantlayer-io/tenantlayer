package io.tenantlayer.core;

import java.util.Objects;

/**
 * The unit of isolation for one unit of work.
 *
 * Carries more than v0.1 reads on purpose. {@code actor} and {@code group} exist for the
 * MSP model (a partner operating on a client's tenant) and {@code region} for residency.
 * Nothing uses them yet; changing this shape later would break every propagation adapter
 * and every custom resolver, so it is fixed now while that costs nothing.
 */
public record TenantScope(String subject, String actor, String group, String region) {

    public TenantScope {
        Objects.requireNonNull(subject, "subject tenant must not be null");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject tenant must not be blank");
        }
    }

    public static TenantScope of(String subject) {
        return new TenantScope(subject, null, null, null);
    }
}
