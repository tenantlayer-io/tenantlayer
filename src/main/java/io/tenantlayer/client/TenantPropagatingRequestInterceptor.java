package io.tenantlayer.client;

import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Feature 16 — puts the current tenant on outbound calls made with {@code RestTemplate}
 * or {@code RestClient}.
 *
 * <h2>What this is and is not</h2>
 *
 * This is context <em>propagation</em>, so the service on the other end knows which tenant
 * the work belongs to. It is emphatically not authentication: the receiving service must
 * treat the header exactly as it would treat any client-supplied header, which is to say
 * it must not trust it unless the caller is inside a trust boundary that guarantees it.
 * A service exposed to the internet should be resolving tenants from tokens (feature 4)
 * and verifying membership (feature 52), not believing this header.
 *
 * <p>An outbound call made with no tenant bound sends no header at all, rather than an
 * empty one. An empty header value is a claim about the tenant; its absence is not.
 */
public class TenantPropagatingRequestInterceptor implements ClientHttpRequestInterceptor {

    private final String headerName;

    public TenantPropagatingRequestInterceptor(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {

        TenantContext.current().map(TenantScope::subject)
                .ifPresent(tenant -> request.getHeaders().set(headerName, tenant));
        return execution.execute(request, body);
    }

    public String headerName() {
        return headerName;
    }
}
