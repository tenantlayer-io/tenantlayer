package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * The same service, the same endpoints, the same entity — isolated by which database the
 * connection was opened against rather than by a policy.
 *
 * <p>Note what is absent from these schemas: no row-level security, no policy, no
 * {@code current_setting('tenantlayer.tenant')}. If the routing stops working, acme reads
 * globex's orders and nothing downstream would catch it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DatabasePerTenantOverHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("shared")
            .withUsername("admin")
            .withPassword("admin_pwd");

    @DynamicPropertySource
    static void routing(DynamicPropertyRegistry registry) {
        // The application's own datasource still points at the shared database — it is what
        // the registry reads. Tenant data lives elsewhere entirely.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("tenantlayer.strategy", () -> "DATABASE_PER_TENANT");
        /* Required under this strategy. Hibernate determines its dialect at start-up by
           asking a connection for its metadata, and at start-up there is no tenant — so
           there is no database to ask. Declaring the dialect skips that probe. */
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        for (String tenant : new String[] {"acme", "globex"}) {
            registry.add("tenantlayer.databases." + tenant + ".url", () -> urlFor("orders_" + tenant));
            registry.add("tenantlayer.databases." + tenant + ".username", POSTGRES::getUsername);
            registry.add("tenantlayer.databases." + tenant + ".password", POSTGRES::getPassword);
        }
    }

    private static String urlFor(String database) {
        return POSTGRES.getJdbcUrl().replaceFirst("/shared\\b", "/" + database);
    }

    @BeforeAll
    static void prepareDatabases() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = admin.createStatement()) {
            s.execute("create database orders_acme");
            s.execute("create database orders_globex");
            // The registry is shared: it answers who the tenants are, before any is known.
            s.execute("""
                    create table tenantlayer_tenants (
                        tenant_id      varchar(64) primary key,
                        status         varchar(16) not null default 'ACTIVE',
                        region         varchar(64),
                        tenant_group   varchar(64),
                        datasource_ref varchar(128),
                        metadata       jsonb not null default '{}'::jsonb)""");
            s.execute("insert into tenantlayer_tenants (tenant_id) values ('acme'), ('globex')");
        }
        seed("orders_acme", "acme");
        seed("orders_globex", "globex");
    }

    /** No policy and no GUC: in a database-per-tenant deployment the database knows whose it is. */
    private static void seed(String database, String tenant) throws Exception {
        try (Connection c = DriverManager.getConnection(
                        urlFor(database), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("""
                    create table orders (
                        id           bigserial primary key,
                        tenant_id    varchar(64)  not null default '%s',
                        customer     varchar(255) not null,
                        item         varchar(255) not null,
                        amount_cents bigint       not null,
                        status       varchar(32)  not null,
                        placed_at    timestamptz  not null default now())"""
                    .formatted(tenant));
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("two tenants share endpoints and are separated by their databases alone")
    void tenantsAreIsolatedByDatabase() {
        long acmeOrder = place("acme", "Wile E. Coyote", "Anvil", 4999);
        place("globex", "Hank Scorpio", "Doomsday device", 999999);

        JsonNode acme = list("acme");
        assertThat(acme).hasSize(1);
        assertThat(acme.get(0).get("item").asText()).isEqualTo("Anvil");
        assertThat(acme.get(0).get("tenantId").asText())
                .as("stamped by acme's own database; the service never sent a tenant")
                .isEqualTo("acme");

        JsonNode globex = list("globex");
        assertThat(globex).hasSize(1);
        assertThat(globex.get(0).get("item").asText()).isEqualTo("Doomsday device");

        assertThat(get("/orders/" + acmeOrder, "acme").getStatusCode())
                .as("acme must still read its own order")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a tenant with no database configured is refused, not served someone else's")
    void unknownTenantIsRefused() {
        ResponseEntity<String> response = get("/orders", "initech");
        assertThat(response.getStatusCode())
                .as("initech has no database; it must not fall through to the shared one")
                .isNotEqualTo(HttpStatus.OK);
    }

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
