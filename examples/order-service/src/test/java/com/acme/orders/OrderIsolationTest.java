package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
 * The real proof: isolation over HTTP, in a service whose own code contains no tenancy
 * logic whatsoever.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderIsolationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("orders")
            .withUsername("admin")
            .withPassword("admin_pwd");

    private static final String APP_USER = "orders_app";
    private static final String APP_PASSWORD = "orders_pwd";

    /**
     * The service connects as a role that is neither superuser nor table owner. A
     * superuser bypasses row-level security outright, so connecting as one would leave
     * the policy in place and never applied.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    @BeforeAll
    static void prepareDatabase() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {

            statement.execute("drop role if exists " + APP_USER);
            statement.execute("create role " + APP_USER + " login password '" + APP_PASSWORD + "'");
            statement.execute(readSchema());
            statement.execute("grant usage on schema public to " + APP_USER);
            statement.execute("grant select, insert, update, delete on orders to " + APP_USER);
            statement.execute("grant usage, select on all sequences in schema public to " + APP_USER);
        }
    }

    private static String readSchema() throws Exception {
        try (var in = new ClassPathResource("schema.sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("two tenants use the same endpoints and never see each other's orders")
    void tenantsAreIsolatedOverHttp() {
        long acmeOrder = place("acme", "Wile E. Coyote", "Anvil", 4999);
        long globexOrder = place("globex", "Hank Scorpio", "Doomsday device", 999999);

        JsonNode acmeList = list("acme");
        assertThat(acmeList).hasSize(1);
        assertThat(acmeList.get(0).get("item").asText()).isEqualTo("Anvil");
        assertThat(acmeList.get(0).get("tenantId").asText())
                .as("the database stamped ownership; the service never sent a tenant")
                .isEqualTo("acme");

        JsonNode globexList = list("globex");
        assertThat(globexList).hasSize(1);
        assertThat(globexList.get(0).get("item").asText()).isEqualTo("Doomsday device");

        // acme knows globex's order id and asks for it directly.
        ResponseEntity<String> stolen = get("/orders/" + globexOrder, "acme");
        assertThat(stolen.getStatusCode())
                .as("acme fetched globex's order by id")
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> ownOrder = get("/orders/" + acmeOrder, "acme");
        assertThat(ownOrder.getStatusCode())
                .as("acme must still be able to read its own order")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a request with no tenant header is rejected, not served empty")
    void requestWithoutTenantIsRejected() {
        ResponseEntity<String> response =
                http.exchange("/orders", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode())
                .as("strict mode must reject rather than quietly return nothing")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ helpers

    private long place(String tenant, String customer, String item, long amountCents) {
        HttpHeaders headers = headers(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"customer":"%s","item":"%s","amountCents":%d}
                """.formatted(customer, item, amountCents);

        ResponseEntity<JsonNode> response =
                http.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    private JsonNode list(String tenant) {
        ResponseEntity<JsonNode> response = http.exchange(
                "/orders", HttpMethod.GET, new HttpEntity<>(headers(tenant)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> get(String path, String tenant) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(tenant)), String.class);
    }

    private HttpHeaders headers(String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", tenant);
        return headers;
    }
}
