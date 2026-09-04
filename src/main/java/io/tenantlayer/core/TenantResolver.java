package io.tenantlayer.core;

import java.util.Optional;

/**
 * Works out which tenant a unit of work belongs to.
 *
 * This is the product's public extension point. Implement it for any scheme the built-in
 * resolvers do not cover; return {@link Optional#empty()} when this resolver has no
 * opinion, so a chain can fall through to the next one.
 *
 * @param <S> the source a tenant is read from — an HTTP request, a message, a job context
 */
@FunctionalInterface
public interface TenantResolver<S> {

    Optional<String> resolve(S source);
}
