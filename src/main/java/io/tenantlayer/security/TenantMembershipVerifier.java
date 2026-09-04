package io.tenantlayer.security;

/**
 * Feature 52 — answers the question the header resolver cannot: is the caller actually
 * entitled to act as the tenant they claimed?
 *
 * <h2>The hole this closes</h2>
 *
 * Resolution and authorisation are different questions, and shipping only the first is how
 * a tenancy layer ends up trusting {@code X-Tenant-ID}. A resolver reports which tenant a
 * request <em>says</em> it is for. Anyone who can reach the port can say anything. This
 * interface is where that claim gets checked against an authenticated identity, so a
 * request from acme's user asking for globex is rejected rather than served.
 *
 * <h2>Why no Spring Security types in the signature</h2>
 *
 * {@code TenantFilter} calls this, and {@code TenantFilter} must keep working in
 * applications that have no Spring Security on the classpath at all. Implementations read
 * whatever identity their environment provides — the {@code SecurityContextHolder}, a
 * mutual-TLS certificate, an internal service token — and keep that dependency to
 * themselves.
 *
 * <h2>Fail closed</h2>
 *
 * Return {@code false} when there is no authenticated principal. "I could not tell" and
 * "yes" must never be the same answer.
 */
@FunctionalInterface
public interface TenantMembershipVerifier {

    boolean isMember(String tenantId);
}
