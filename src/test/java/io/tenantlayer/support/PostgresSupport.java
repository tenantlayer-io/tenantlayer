package io.tenantlayer.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One container for the whole suite.
 *
 * Two DataSources, and the distinction matters: Postgres superusers bypass RLS entirely,
 * so if the application connected as the container's default user every isolation test
 * would pass for the wrong reason. The app connects as a least-privileged role; the
 * superuser connection exists only to set up and to verify the other tenant's rows are
 * really there.
 */
public final class PostgresSupport {

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("tenantlayer")
                    .withUsername("postgres_admin")
                    .withPassword("admin_pwd");

    private static final String APP_USER = "app_user";
    private static final String APP_PASSWORD = "app_pwd";

    private static volatile HikariDataSource privileged;

    private PostgresSupport() {
    }

    public static synchronized void start() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
            privileged = pool("privileged", CONTAINER.getJdbcUrl(),
                    CONTAINER.getUsername(), CONTAINER.getPassword(), 4);
            createApplicationRole();
        }
    }

    /** Reapplies schema, policy and seed data. Idempotent, run before each test class. */
    public static void resetSchema() {
        start();
        execute(readSchema());
        execute("grant usage on schema public to " + APP_USER + ";"
                + "grant select, insert, update, delete on documents to " + APP_USER + ";"
                + "grant select, insert, update, delete on tenantlayer_tenants to " + APP_USER + ";"
                + "grant select, insert, update, delete on notes to " + APP_USER + ";"
                + "grant select, insert, update, delete on invoices to " + APP_USER + ";"
                + "grant usage, select on all sequences in schema public to " + APP_USER + ";");
    }

    private static void createApplicationRole() {
        execute("drop role if exists " + APP_USER + ";"
                + "create role " + APP_USER + " login password '" + APP_PASSWORD + "';");
    }

    public static DataSource privileged() {
        start();
        return privileged;
    }

    /**
     * @param maxPoolSize 1 forces every request onto the same physical connection, which
     *                    is how the pooled-connection leak test makes reuse deterministic.
     */
    public static HikariDataSource applicationPool(int maxPoolSize) {
        start();
        return pool("application", CONTAINER.getJdbcUrl(), APP_USER, APP_PASSWORD, maxPoolSize);
    }

    private static HikariDataSource pool(String name, String url, String user, String password, int size) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setPoolName(name);
        cfg.setMaximumPoolSize(size);
        return new HikariDataSource(cfg);
    }

    private static void execute(String sql) {
        try (Connection connection = privileged.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("setup SQL failed: " + sql, e);
        }
    }

    private static String readSchema() {
        try (var in = new ClassPathResource("schema.sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not read schema.sql", e);
        }
    }
}
