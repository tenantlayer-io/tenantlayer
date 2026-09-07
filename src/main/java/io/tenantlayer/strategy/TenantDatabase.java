package io.tenantlayer.strategy;

/**
 * Where one tenant's database is and how to reach it.
 *
 * @param url      JDBC URL, required
 * @param username may be null when the URL carries it
 * @param password may be null
 * @param maxPoolSize per-tenant pool ceiling; null leaves the pool's own default
 */
public record TenantDatabase(String url, String username, String password, Integer maxPoolSize) {

    public TenantDatabase {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("a JDBC url is required");
        }
    }

    public static TenantDatabase of(String url) {
        return new TenantDatabase(url, null, null, null);
    }
}
