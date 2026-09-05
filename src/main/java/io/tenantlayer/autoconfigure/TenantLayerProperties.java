package io.tenantlayer.autoconfigure;

import io.tenantlayer.registry.TenantRegistrySchema;
import io.tenantlayer.security.ClaimTenantMembershipVerifier;
import io.tenantlayer.web.HeaderTenantResolver;
import io.tenantlayer.web.JwtClaimTenantResolver;
import io.tenantlayer.web.PathSegmentTenantResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tenantlayer")
public class TenantLayerProperties {

    /** Request header the default resolver reads. */
    private String header = HeaderTenantResolver.DEFAULT_HEADER;

    /**
     * Reject requests with no resolvable tenant. On by default: silently proceeding
     * without a tenant is how isolation bugs become "why is this list empty" tickets.
     */
    private boolean strict = true;

    /** Paths served without a tenant — health checks, docs, the login endpoint. */
    private List<String> unscopedPaths = new ArrayList<>(List.of("/actuator", "/error"));

    /**
     * Which resolvers run, in order. First match wins, so list the source you trust most
     * first — a spoofable header should never outrank a signed claim.
     */
    private List<Source> resolvers = new ArrayList<>(List.of(Source.HEADER));

    /**
     * Base domain the subdomain resolver strips, e.g. "app.com" so acme.app.com yields
     * acme. Left unset, the first label of the host is used.
     */
    private String baseDomain;

    /** Prefix the path-segment resolver matches, e.g. "/t" for /t/acme/orders. */
    private String pathPrefix = PathSegmentTenantResolver.DEFAULT_PREFIX;

    /**
     * Feature 24 — how connections are isolated. Changing this is start-up configuration,
     * never per request.
     *
     * <p>Note that the three strategies fail closed <em>differently</em> when no tenant is
     * bound: row-level security yields an empty result set, schema-per-tenant raises an
     * unresolved-relation error. All are safe, but an application that silently copes with
     * empty results will fail loudly under the other, so switching is not
     * behaviour-preserving on the no-tenant path.
     */
    private Strategy strategy = Strategy.ROW_LEVEL_SECURITY;

    /** Feature 4 — JWT claim the tenant is read from. */
    private String jwtClaim = JwtClaimTenantResolver.DEFAULT_CLAIM;

    /**
     * Servlet filter order. Left null it is derived: normally near-first, so no database
     * work can precede it, but moved after Spring Security's chain when the tenant or the
     * caller's membership is read from an authenticated principal — otherwise the filter
     * would run before anyone has been authenticated and see nothing.
     */
    private Integer filterOrder;

    private final Membership membership = new Membership();
    private final Schema schema = new Schema();
    private final Discriminator discriminator = new Discriminator();
    private final Caching cache = new Caching();
    private final Registry registry = new Registry();

    public enum Strategy {
        /** One shared schema; Postgres row-level security enforces. The default. */
        ROW_LEVEL_SECURITY,
        /** One schema per tenant on a shared pool, selected by search_path. */
        SCHEMA_PER_TENANT
    }

    public enum Source {
        HEADER, SUBDOMAIN, PATH, JWT;

        /** True for sources that require Spring Security to have already authenticated. */
        public boolean requiresAuthentication() {
            return this == JWT;
        }
    }

    /** Feature 52. */
    public static class Membership {

        /**
         * Verify that the authenticated principal is entitled to the resolved tenant.
         * Off by default because turning it on without a token that carries tenant claims
         * would reject every request; on is the correct setting for anything exposed
         * directly to callers.
         */
        private boolean enabled = false;

        /** Token claim listing the tenants the bearer may act as. */
        private String claim = ClaimTenantMembershipVerifier.DEFAULT_CLAIM;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getClaim() {
            return claim;
        }

        public void setClaim(String claim) {
            this.claim = claim;
        }
    }

    /** Features 21 and 30. */
    public static class Schema {

        /** Prefix for the per-tenant schema name under SCHEMA_PER_TENANT. */
        private String prefix = "tenant_";

        /** Column that marks a table tenant-scoped. */
        private String tenantColumn = io.tenantlayer.schema.TenantScopedEntityScanner
                .DEFAULT_TENANT_COLUMN;

        /** Tables or entity names to treat as tenant-scoped regardless of their columns. */
        private java.util.Set<String> includes = new java.util.LinkedHashSet<>();

        /**
         * Tables or entity names never to treat as tenant-scoped. The convention will be
         * wrong somewhere — a shared reference table with a tenant_id audit column is the
         * usual case — and being wrong here means generating a policy that hides rows
         * everyone is supposed to see.
         */
        private java.util.Set<String> excludes = new java.util.LinkedHashSet<>();

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getTenantColumn() {
            return tenantColumn;
        }

        public void setTenantColumn(String tenantColumn) {
            this.tenantColumn = tenantColumn;
        }

        public java.util.Set<String> getIncludes() {
            return includes;
        }

        public void setIncludes(java.util.Set<String> includes) {
            this.includes = includes;
        }

        public java.util.Set<String> getExcludes() {
            return excludes;
        }

        public void setExcludes(java.util.Set<String> excludes) {
            this.excludes = excludes;
        }
    }

    /** Feature 20. */
    public static class Discriminator {

        /**
         * Register the Hibernate tenant identifier resolver, which is what makes
         * {@code @TenantId} work. On by default and harmless without any annotated
         * entity; off is for applications that configure their own resolver.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Features 96 and 97. */
    public static class Caching {

        /** Qualify cache keys by tenant. */
        private boolean enabled = true;

        /**
         * Caches that are NOT tenant-scoped and should be left alone — shared reference
         * data, feature flags, and the like.
         *
         * <p>Everything not named here is treated as tenant-scoped. That default is the
         * opposite of convenient, deliberately: opting in would mean a cache someone
         * forgot to configure leaks across tenants silently, while opting out means a
         * forgotten one costs hit rate on shared data, which shows up in a dashboard
         * rather than in a breach.
         */
        private java.util.Set<String> shared = new java.util.LinkedHashSet<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.util.Set<String> getShared() {
            return shared;
        }

        public void setShared(java.util.Set<String> shared) {
            this.shared = shared;
        }
    }

    /** Feature 50. */
    public static class Registry {

        /** Expose a JdbcTenantRegistry bean backed by the application DataSource. */
        private boolean enabled = true;

        /** Table the registry reads and writes. */
        private String table = TenantRegistrySchema.DEFAULT_TABLE;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }
    }

    /** True when any configured resolver reads from an authenticated principal. */
    public boolean resolvesFromAuthentication() {
        return resolvers.stream().anyMatch(Source::requiresAuthentication);
    }

    public List<Source> getResolvers() {
        return resolvers;
    }

    public void setResolvers(List<Source> resolvers) {
        this.resolvers = resolvers;
    }

    public String getBaseDomain() {
        return baseDomain;
    }

    public void setBaseDomain(String baseDomain) {
        this.baseDomain = baseDomain;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public List<String> getUnscopedPaths() {
        return unscopedPaths;
    }

    public void setUnscopedPaths(List<String> unscopedPaths) {
        this.unscopedPaths = unscopedPaths;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public String getJwtClaim() {
        return jwtClaim;
    }

    public void setJwtClaim(String jwtClaim) {
        this.jwtClaim = jwtClaim;
    }

    public Integer getFilterOrder() {
        return filterOrder;
    }

    public void setFilterOrder(Integer filterOrder) {
        this.filterOrder = filterOrder;
    }

    public Membership getMembership() {
        return membership;
    }

    public Registry getRegistry() {
        return registry;
    }

    public Schema getSchema() {
        return schema;
    }

    public Discriminator getDiscriminator() {
        return discriminator;
    }

    public Caching getCache() {
        return cache;
    }
}
