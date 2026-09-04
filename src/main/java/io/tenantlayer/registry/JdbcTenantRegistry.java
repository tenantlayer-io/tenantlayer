package io.tenantlayer.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Feature 50 — the registry, backed by one table.
 *
 * <p>Plain JDBC rather than a JPA entity, on purpose. This is consulted during tenant
 * resolution, which happens in a servlet filter before any {@code EntityManager} is
 * opened and before a transaction exists; going through JPA would mean either an awkward
 * second persistence unit or a dependency on the application's own transaction
 * boundaries. A prepared statement has neither problem.
 *
 * <p>The table name is configurable because {@code tenantlayer_tenants} will collide with
 * something in somebody's schema eventually. It is interpolated into SQL, so it is
 * validated as an identifier at construction rather than trusted.
 */
public class JdbcTenantRegistry implements TenantRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> METADATA_TYPE =
            new TypeReference<>() { };

    private final DataSource dataSource;
    private final String table;

    public JdbcTenantRegistry(DataSource dataSource) {
        this(dataSource, TenantRegistrySchema.DEFAULT_TABLE);
    }

    public JdbcTenantRegistry(DataSource dataSource, String table) {
        this.dataSource = dataSource;
        this.table = requireIdentifier(table);
    }

    /**
     * The table name reaches SQL by string concatenation because a table cannot be a bind
     * parameter. That makes validating it the only thing standing between a configuration
     * property and an injection, so it is checked rather than assumed.
     */
    private static String requireIdentifier(String candidate) {
        if (candidate == null || !candidate.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(
                    "registry table must be a plain SQL identifier, got: " + candidate);
        }
        return candidate;
    }

    private static final String COLUMNS =
            "tenant_id, status, region, tenant_group, datasource_ref, metadata";

    @Override
    public Optional<TenantRegistration> find(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        String sql = "select " + COLUMNS + " from " + table + " where tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new TenantRegistryException("looking up tenant '" + tenantId + "' failed", e);
        }
    }

    @Override
    public List<TenantRegistration> findAll() {
        String sql = "select " + COLUMNS + " from " + table + " order by tenant_id";
        List<TenantRegistration> all = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                all.add(read(rs));
            }
            return all;
        } catch (SQLException e) {
            throw new TenantRegistryException("listing tenants failed", e);
        }
    }

    @Override
    public List<String> activeTenantIds() {
        String sql = "select tenant_id from " + table
                + " where status = 'ACTIVE' order by tenant_id";
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new TenantRegistryException("listing active tenants failed", e);
        }
    }

    @Override
    public void save(TenantRegistration registration) {
        String sql = "insert into " + table + " (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?)"
                + " on conflict (tenant_id) do update set"
                + " status = excluded.status, region = excluded.region,"
                + " tenant_group = excluded.tenant_group,"
                + " datasource_ref = excluded.datasource_ref,"
                + " metadata = excluded.metadata";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, registration.tenantId());
            statement.setString(2, registration.status().name());
            statement.setString(3, registration.region());
            statement.setString(4, registration.group());
            statement.setString(5, registration.datasourceRef());
            statement.setObject(6, writeMetadata(registration.metadata()), Types.OTHER);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new TenantRegistryException(
                    "saving tenant '" + registration.tenantId() + "' failed", e);
        }
    }

    @Override
    public boolean delete(String tenantId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("delete from " + table + " where tenant_id = ?")) {
            statement.setString(1, tenantId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new TenantRegistryException("deleting tenant '" + tenantId + "' failed", e);
        }
    }

    private TenantRegistration read(ResultSet rs) throws SQLException {
        return new TenantRegistration(
                rs.getString("tenant_id"),
                TenantStatus.parse(rs.getString("status")),
                rs.getString("region"),
                rs.getString("tenant_group"),
                rs.getString("datasource_ref"),
                readMetadata(rs.getString("metadata")));
    }

    private Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new LinkedHashMap<>(JSON.readValue(json, METADATA_TYPE));
        } catch (Exception e) {
            throw new TenantRegistryException("registry metadata is not a JSON object: " + json, e);
        }
    }

    private String writeMetadata(Map<String, String> metadata) {
        try {
            return JSON.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new TenantRegistryException("registry metadata could not be serialised", e);
        }
    }
}
