package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.tenantlayer.check.IsolationChecker;
import io.tenantlayer.check.IsolationFinding;
import io.tenantlayer.check.IsolationFinding.Severity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The isolation checker, from a real application rather than from the library's own tests.
 *
 * <p>This service is set up correctly, so the checker should be silent. The test then breaks
 * the database the way a team actually breaks it — someone drops a policy, someone adds a
 * table without one — and asserts the checker notices. A checker that stays quiet either way
 * would pass a test that only ever looked at the healthy case.
 */
@SpringBootTest
@Testcontainers
class IsolationCheckOverHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("orders")
            .withUsername("admin")
            .withPassword("admin_pwd");

    private static final String APP_USER = "orders_app";
    private static final String APP_PASSWORD = "orders_pwd";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    @BeforeAll
    static void prepareDatabase() throws Exception {
        execute("drop role if exists " + APP_USER,
                "create role " + APP_USER + " login password '" + APP_PASSWORD + "'",
                readSchema(),
                "grant usage on schema public to " + APP_USER,
                "grant select, insert, update, delete on orders to " + APP_USER,
                "grant select, insert, update, delete on tenantlayer_tenants to " + APP_USER,
                "grant usage, select on all sequences in schema public to " + APP_USER);
    }

    @AfterEach
    void restoreThePolicy() throws Exception {
        execute("""
                drop table if exists strays;
                alter table orders enable row level security;
                alter table orders force row level security;
                drop policy if exists tenant_isolation on orders;
                create policy tenant_isolation on orders
                    using (tenant_id = nullif(current_setting('tenantlayer.tenant', true), ''));
                """);
    }

    @Autowired
    private IsolationChecker checker;

    @Test
    @DisplayName("a correctly configured service reports nothing")
    void healthyServiceIsSilent() {
        assertThat(checker.check())
                .as("the checker found a problem in a service that is set up correctly")
                .isEmpty();
    }

    @Test
    @DisplayName("dropping the policy is reported as not enforced")
    void droppedPolicyIsCaught() throws Exception {
        execute("drop policy tenant_isolation on orders");

        List<IsolationFinding> findings = checker.check();

        assertThat(findings).isNotEmpty();
        assertThat(findings).anyMatch(f ->
                f.subject().equals("orders")
                        && f.severity() == Severity.NOT_ENFORCED
                        && f.problem().contains("no policy"));
    }

    @Test
    @DisplayName("turning row-level security off entirely is reported")
    void disabledRowLevelSecurityIsCaught() throws Exception {
        execute("alter table orders disable row level security");

        assertThat(checker.check()).anyMatch(f ->
                f.subject().equals("orders")
                        && f.severity() == Severity.NOT_ENFORCED
                        && f.problem().contains("not enabled"));
    }

    @Test
    @DisplayName("losing FORCE is reported even though the policy is still there")
    void missingForceIsCaught() throws Exception {
        execute("alter table orders no force row level security");

        assertThat(checker.check()).anyMatch(f ->
                f.subject().equals("orders") && f.problem().contains("FORCE ROW LEVEL SECURITY"));
    }

    private static void execute(String... statements) throws Exception {
        try (Connection admin = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static String readSchema() throws Exception {
        try (var in = new ClassPathResource("schema.sql").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
