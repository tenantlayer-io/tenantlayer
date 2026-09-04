package io.tenantlayer.client;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.util.Optional;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Feature 16, WebClient half.
 *
 * <p>The tenant is read when the filter is applied — on the subscribing thread, while the
 * caller's context is still current — rather than deferred into the reactive pipeline,
 * where the work may already have hopped onto a scheduler thread that never had one. Full
 * Reactor context propagation is feature 13 and belongs to v0.2; this covers the common
 * case of a WebClient call made from an ordinary blocking request thread.
 */
public class TenantPropagatingExchangeFilter implements ExchangeFilterFunction {

    private final String headerName;

    public TenantPropagatingExchangeFilter(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public Mono<org.springframework.web.reactive.function.client.ClientResponse> filter(
            ClientRequest request, ExchangeFunction next) {

        Optional<String> tenant = TenantContext.current().map(TenantScope::subject);
        if (tenant.isEmpty()) {
            return next.exchange(request);
        }
        return next.exchange(ClientRequest.from(request)
                .header(headerName, tenant.get())
                .build());
    }

    public String headerName() {
        return headerName;
    }
}
