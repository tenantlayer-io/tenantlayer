package io.tenantlayer.registry;

/**
 * A tenant's metadata is missing a key the caller required, or holds a value that is not
 * valid for the {@link MetadataKey} it was read with.
 *
 * <p>The message names the tenant and the key but deliberately never the stored value:
 * metadata is free-form, and a value that fails to parse is as likely to be a pasted secret
 * as a typo. Exceptions end up in logs, so the value stays out of them.
 */
public class TenantMetadataException extends TenantRegistryException {

    /** @param cause normally {@code null}: a parse failure's own message quotes the value. */
    public TenantMetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
