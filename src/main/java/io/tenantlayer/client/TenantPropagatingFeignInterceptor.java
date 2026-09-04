package io.tenantlayer.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.tenantlayer.core.TenantContext;
import io.tenantlayer.core.TenantScope;

/**
 * Feature 16, Feign half. Same contract as the {@code RestTemplate} interceptor: the
 * header is set when a tenant is bound and omitted entirely when one is not.
 */
public class TenantPropagatingFeignInterceptor implements RequestInterceptor {

    private final String headerName;

    public TenantPropagatingFeignInterceptor(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public void apply(RequestTemplate template) {
        TenantContext.current().map(TenantScope::subject)
                .ifPresent(tenant -> template.header(headerName, tenant));
    }

    public String headerName() {
        return headerName;
    }
}
