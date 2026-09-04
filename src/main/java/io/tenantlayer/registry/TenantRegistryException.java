package io.tenantlayer.registry;

/** The registry could not be read or written. Never means "tenant not found". */
public class TenantRegistryException extends RuntimeException {

    public TenantRegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
