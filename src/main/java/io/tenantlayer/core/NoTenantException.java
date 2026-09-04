package io.tenantlayer.core;

/** Thrown when work reaches the database with no tenant resolved. Fail closed, never default. */
public class NoTenantException extends RuntimeException {

    public NoTenantException(String message) {
        super(message);
    }
}
