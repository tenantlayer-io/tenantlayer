package io.tenantlayer.core;

import java.util.List;
import java.util.Optional;

/**
 * Feature 6 — ordered resolvers, first match wins.
 *
 * Real applications mix schemes: a browser session arrives on a subdomain, the public API
 * carries a token, an internal caller sets a header. Order is precedence, so put the
 * source you trust most first — a spoofable header should never outrank a signed claim.
 */
public class TenantResolverChain<S> implements TenantResolver<S> {

    private final List<TenantResolver<S>> delegates;

    public TenantResolverChain(List<TenantResolver<S>> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("a resolver chain needs at least one resolver");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public Optional<String> resolve(S source) {
        for (TenantResolver<S> delegate : delegates) {
            Optional<String> tenant = delegate.resolve(source);
            if (tenant.isPresent()) {
                return tenant;
            }
        }
        return Optional.empty();
    }

    public List<TenantResolver<S>> delegates() {
        return delegates;
    }
}
