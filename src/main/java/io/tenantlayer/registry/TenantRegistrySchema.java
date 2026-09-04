package io.tenantlayer.registry;

/**
 * The registry table, as SQL you can read before you run it.
 *
 * <p>Deliberately not created by Hibernate {@code ddl-auto}. This table is operational
 * infrastructure that outlives any one deployment, so it belongs in the application's own
 * migration tool (Flyway or Liquibase, features 44 and 45) where it is versioned and
 * reviewed like everything else. The constant is here so nobody has to reverse-engineer
 * the column names from the queries.
 */
public final class TenantRegistrySchema {

    public static final String DEFAULT_TABLE = "tenantlayer_tenants";

    /**
     * Note what is <em>absent</em>: no {@code enable row level security}. The registry is
     * shared infrastructure read during tenant resolution, before any tenant is known. A
     * policy here would hide the registry from the code whose job is to consult it.
     *
     * <p>{@code tenant_group} rather than {@code group} because {@code GROUP} is a reserved
     * word in SQL and quoting it forever is worse than naming it well once.
     */
    public static final String DDL = """
            create table if not exists %s (
                tenant_id      varchar(64) primary key,
                status         varchar(16)  not null default 'ACTIVE',
                region         varchar(64),
                tenant_group   varchar(64),
                datasource_ref varchar(128),
                metadata       jsonb        not null default '{}'::jsonb
            );
            create index if not exists idx_%s_group  on %s (tenant_group);
            create index if not exists idx_%s_region on %s (region);
            """;

    private TenantRegistrySchema() {
    }

    public static String ddlFor(String table) {
        return DDL.formatted(table, table, table, table, table);
    }
}
