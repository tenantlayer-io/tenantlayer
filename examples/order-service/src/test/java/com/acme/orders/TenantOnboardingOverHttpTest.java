package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tenantlayer.registry.TenantProvisioning;
import io.tenantlayer.registry.TenantProvisioningException;
import io.tenantlayer.registry.TenantProvisioningHook;
import io.tenantlayer.registry.TenantRegistry;
import io.tenantlayer.registry.TenantStatus;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Features 67 and 53, from a real service.
 *
 * <p>The seeding hook writes an order through the ordinary repository. Under row-level
 * security that insert only succeeds if the new tenant is bound while the hook runs — the
 * policy rejects it otherwise. So this test fails outright if provisioning stops binding
 * the tenant, which is the mistake the feature exists to prevent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantOnboardingOverHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("orders")
            .withUsername("admin")
            .withPassword("admin_pwd");

    private static final String APP_USER = "orders_app";
    private static final String APP_PASSWORD = "orders_pwd";

    /** Flipped on to make the hook fail, for the half-created-tenant case. */
    static final AtomicBoolean SEEDING_FAILS = new AtomicBoolean(false);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    @TestConfiguration
    static class SeedingHook {
        @Bean
        TenantProvisioningHook seedStarterOrder(OrderRepository orders) {
            return tenantId -> {
                if (SEEDING_FAILS.get()) {
                    throw new IllegalStateException("seeding blew up");
                }
                // No tenant parameter anywhere. The policy stamps and scopes this row,
                // which only works because provisioning bound the tenant first.
                orders.save(new Order("Welcome", "Starter plan", 0));
            };
        }
    }

    @BeforeAll
    static void prepareDatabase() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("drop role if exists " + APP_USER);
            statement.execute("create role " + APP_USER + " login password '" + APP_PASSWORD + "'");
            try (var in = new ClassPathResource("schema.sql").getInputStream()) {
                statement.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            statement.execute("grant usage on schema public to " + APP_USER);
            statement.execute("grant select, insert, update, delete on orders to " + APP_USER);
            statement.execute("grant select, insert, update, delete on tenantlayer_tenants to " + APP_USER);
            statement.execute("grant usage, select on all sequences in schema public to " + APP_USER);
        }
    }

    @Autowired
    private TenantProvisioning provisioning;

    @Autowired
    private TenantRegistry registry;

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("a newly onboarded tenant is active and can immediately be served")
    void onboardingProducesAUsableTenant() {
        SEEDING_FAILS.set(false);
        provisioning.onboard("newcorp");

        assertThat(registry.find("newcorp").orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);

        // The seeded row is visible to the new tenant, and to nobody else.
        assertThat(get("/orders", "newcorp").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/orders", "newcorp").getBody())
                .as("the hook's seed row was not written as the new tenant")
                .contains("Starter plan");
        assertThat(get("/orders", "acme").getBody())
                .as("newcorp's seed data leaked to another tenant")
                .doesNotContain("Starter plan");
    }

    @Test
    @DisplayName("onboarding the same tenant twice does not seed it twice")
    void onboardingIsIdempotent() {
        SEEDING_FAILS.set(false);
        provisioning.onboard("twicecorp");
        String afterFirst = get("/orders", "twicecorp").getBody();

        provisioning.onboard("twicecorp");

        assertThat(get("/orders", "twicecorp").getBody())
                .as("a retried signup must not seed a second time")
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a failing hook leaves the tenant PROVISIONING, and it is not served")
    void aFailedOnboardingLeavesNothingUsable() {
        SEEDING_FAILS.set(true);
        try {
            assertThatThrownBy(() -> provisioning.onboard("brokencorp"))
                    .isInstanceOf(TenantProvisioningException.class);

            assertThat(registry.find("brokencorp").orElseThrow().status())
                    .isEqualTo(TenantStatus.PROVISIONING);
            assertThat(registry.activeTenantIds())
                    .as("a half-created tenant must not appear to background jobs")
                    .doesNotContain("brokencorp");
        } finally {
            SEEDING_FAILS.set(false);
        }
    }

    private ResponseEntity<String> get(String path, String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", tenant);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
