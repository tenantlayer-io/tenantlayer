package io.tenantlayer.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/** The record header the tenant travels in, and the encoding both sides agree on. */
public final class TenantHeaders {

    public static final String TENANT_HEADER = "tenantlayer-tenant";

    private TenantHeaders() {
    }

    public static void write(Headers headers, String tenant) {
        headers.remove(TENANT_HEADER);
        headers.add(TENANT_HEADER, tenant.getBytes(StandardCharsets.UTF_8));
    }

    /** @return the tenant carried by the record, or {@code null} if it carries none. */
    public static String read(Headers headers) {
        Header header = headers.lastHeader(TENANT_HEADER);
        if (header == null || header.value() == null || header.value().length == 0) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
