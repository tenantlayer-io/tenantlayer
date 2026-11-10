package io.tenantlayer.registry;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Feature 50 — one row of the tenant registry.
 *
 * <p>Feature 56 is the reason {@code region} and {@code group} are here in v0.1, long
 * before residency (Pro-4) or the MSP module (Pro-6) exist to read them. A registry is the
 * hardest thing in the system to change once it holds production rows: adding a column
 * later means a migration on every deployment plus a backfill nobody has the data for.
 * Both are nullable and unused; they cost a schema no one has to alter twice.
 *
 * <p>{@code datasourceRef} is the same argument for schema- and database-per-tenant
 * routing (features 22 and 23, v0.2/v0.3). It names a datasource; nothing resolves it yet.
 */
public record TenantRegistration(
        String tenantId,
        TenantStatus status,
        String region,
        String group,
        String datasourceRef,
        Map<String, String> metadata) {

    public TenantRegistration {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        status = status == null ? TenantStatus.ACTIVE : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static TenantRegistration of(String tenantId) {
        return new TenantRegistration(tenantId, TenantStatus.ACTIVE, null, null, null, Map.of());
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    /**
     * Feature 55 — one metadata entry, read as the type the key describes.
     *
     * <p>Absent is empty. Present but not valid for the key throws: a value that does not
     * parse is never quietly treated as absent or as a default, because that turns a typo in
     * a flag into a silent change of behaviour. The untyped {@link #metadata()} map is
     * unchanged and keeps working alongside this.
     *
     * @throws TenantMetadataException if the entry is present and invalid for {@code key}
     */
    public <T> Optional<T> metadata(MetadataKey<T> key) {
        Objects.requireNonNull(key, "key must not be null");
        String raw = metadata.get(key.name());
        if (raw == null) {
            return Optional.empty();
        }
        try {
            // Optional.of, not ofNullable: a parser that returns null has produced no valid
            // value, and the NullPointerException lands in the catch below like any other
            // rejection rather than coming back as "absent".
            return Optional.of(key.parse(raw));
        } catch (RuntimeException e) {
            // No cause, and no value: a NumberFormatException quotes the input, and metadata
            // can hold anything, including something that should never reach a log.
            throw new TenantMetadataException(
                    "tenant '" + tenantId + "' has metadata '" + key.name()
                            + "' that is not valid; expected " + key.description(),
                    null);
        }
    }

    /**
     * Like {@link #metadata(MetadataKey)}, for an entry the caller cannot do without.
     *
     * @throws TenantMetadataException if the entry is absent, or present and invalid
     */
    public <T> T requireMetadata(MetadataKey<T> key) {
        return metadata(key).orElseThrow(() -> new TenantMetadataException(
                "tenant '" + tenantId + "' has no metadata '" + key.name()
                        + "'; expected " + key.description(),
                null));
    }

    /**
     * Checks every given key that is present, and returns this registration so it can sit in
     * front of a save: {@code registry.save(registration.validateMetadata(SEATS, PLAN))}.
     * Keys that are absent are not an error here — use {@link #requireMetadata} for that.
     *
     * @throws TenantMetadataException for the first present entry that is invalid
     */
    public TenantRegistration validateMetadata(MetadataKey<?>... keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        for (MetadataKey<?> key : keys) {
            metadata(key);
        }
        return this;
    }
}
