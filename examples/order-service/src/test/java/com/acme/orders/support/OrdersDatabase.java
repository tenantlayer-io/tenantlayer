package com.acme.orders.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres for the whole suite, shared across test classes.
 *
 * The service connects as {@code orders_app}: neither superuser nor table owner. A
 * superuser bypasses row-level security outright, so connecting as one would leave every
 * policy in place and never applied — and every isolation test would be meaningless.
 */
public final class OrdersDatabase {

    public static final String APP_USER = "orders_app";
    public static final String APP_PASSWORD = "orders_pwd";

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("orders")
                    .withUsername("admin")
                    .withPassword("admin_pwd");

    private static volatile HikariDataSource privileged;

    private OrdersDatabase() {
    }

    public static synchronized void start() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
            privileged = pool(CONTAINER.getUsername(), CONTAINER.getPassword());
            // Created once for the whole JVM. Dropping it per class fails as soon as a
            // previous class has granted it anything.
            run("""
                do $$ begin
                    if not exists (select 1 from pg_roles where rolname = '%s') then
                        create role %s login password '%s';
                    end if;
                end $$;""".formatted(APP_USER, APP_USER, APP_PASSWORD));
        }
    }

    /** Drops and rebuilds the schema, so each test class starts from a known state. */
    public static synchronized void reset() {
        start();
        run("drop table if exists orders");
        run("drop table if exists tenantlayer_tenants");
        run(readSchema());
        run("grant usage on schema public to " + APP_USER,
            "grant select, insert, update, delete on orders to " + APP_USER,
            "grant select, insert, update, delete on tenantlayer_tenants to " + APP_USER,
            "grant usage, select on all sequences in schema public to " + APP_USER);
    }

    /** Empties the table between test methods so counts are deterministic. */
    public static synchronized void truncate() {
        start();
        run("truncate table orders restart identity");
    }

    public static void bindDataSource(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    /** Bypasses the policy. Used only to prove rows genuinely exist. */
    public static DataSource privileged() {
        start();
        return privileged;
    }

    public static DataSource applicationDataSource() {
        start();
        return pool(APP_USER, APP_PASSWORD);
    }

    private static HikariDataSource pool(String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(CONTAINER.getJdbcUrl());
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(3);
        return new HikariDataSource(cfg);
    }

    private static void run(String... statements) {
        try (Connection admin = DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement statement = admin.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException("database setup failed", e);
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
