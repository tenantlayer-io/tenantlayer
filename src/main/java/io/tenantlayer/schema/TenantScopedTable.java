package io.tenantlayer.schema;

/** One table the scanner considers tenant-scoped, and the column that scopes it. */
public record TenantScopedTable(String entityName, String tableName, String tenantColumn)
        implements Comparable<TenantScopedTable> {

    @Override
    public int compareTo(TenantScopedTable other) {
        return tableName.compareTo(other.tableName);
    }
}
