package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Feature 51 — the tenants endpoint, over real HTTP, with real exposure configuration.
 *
 * <p>The library's own tests call the endpoint object directly, which says nothing about
 * whether actuator registers it or whether it is reachable. This asserts both, and that a
 * tenant created through it is immediately servable by the ordinary API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantsEndpointOverHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("orders")
            .withUsername("admin")
            .withPassword("admin_pwd");

    private static final String APP_USER = "orders_app";
    private static final String APP_PASSWORD = "orders_pwd";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // Both are required. Neither on its own exposes it.
        registry.add("management.endpoint.tenants.enabled", () -> "true");
        registry.add("management.endpoints.web.exposure.include", () -> "tenants");
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
    private TestRestTemplate http;

    @Test
    @DisplayName("the endpoint is registered and lists the tenants the registry holds")
    void endpointIsReachableAndLists() {
        ResponseEntity<JsonNode> response =
                http.getForEntity("/actuator/tenants", JsonNode.class);

        assertThat(response.getStatusCode())
                .as("the endpoint was not registered or not exposed")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("tenants").isArray()).isTrue();
    }

    @Test
    @DisplayName("a tenant created through the endpoint is immediately usable by the ordinary API")
    void aTenantCreatedHereCanBeServed() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> created = http.exchange(
                "/actuator/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("tenantId", "endpointcorp"), json), JsonNode.class);

        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(created.getBody().get("status").asText()).isEqualTo("ACTIVE");

        // The real proof: the ordinary API now serves it.
        HttpHeaders tenant = new HttpHeaders();
        tenant.set("X-Tenant-ID", "endpointcorp");
        assertThat(http.exchange("/orders", HttpMethod.GET, new HttpEntity<>(tenant), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the endpoint is not tenant-scoped: it needs no tenant header")
    void theEndpointIsNotItselfTenantScoped() {
        // No X-Tenant-ID, and strict mode is on. /actuator is an unscoped path by default,
        // so managing tenants does not require being one.
        ResponseEntity<JsonNode> response = http.exchange(
                "/actuator/tenants", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
