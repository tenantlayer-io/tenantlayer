package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orders.support.Api;
import com.acme.orders.support.OrdersDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Features 1, 2, 3, 6 and 7 — every way the tenant can arrive, driven over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "tenantlayer.resolvers=header,subdomain,path",
                "tenantlayer.base-domain=app.test",
                "tenantlayer.path-prefix=/t"
        })
class TenantResolutionTest {

    static {
        // Host is a restricted header in the JDK's HTTP client and is silently replaced
        // unless this is set before the client class initialises.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        OrdersDatabase.bindDataSource(registry);
    }

    @BeforeAll
    static void prepare() {
        OrdersDatabase.reset();
    }

    @Autowired
    private TestRestTemplate http;

    private Api api;

    @BeforeEach
    void setUp() {
        OrdersDatabase.truncate();
        api = new Api(http);
    }

    @Test
    @DisplayName("feature 1 — the tenant arrives in a header")
    void headerResolver() {
        api.placeAs("acme", "Wile E. Coyote", "Anvil", 4999);

        JsonNode orders = api.getAs("/orders", "acme").getBody();

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).get("tenantId").asText()).isEqualTo("acme");
    }

    @Test
    @DisplayName("feature 2 — the tenant arrives as a subdomain, with no header at all")
    void subdomainResolver() {
        api.placeAs("globex", "Hank Scorpio", "Doomsday device", 999999);

        ResponseEntity<JsonNode> response = api.getWithHost("/orders", "globex.app.test");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).get("tenantId").asText()).isEqualTo("globex");
    }

    @Test
    @DisplayName("feature 2 — a reserved subdomain is not a tenant")
    void reservedSubdomainIsRejected() {
        ResponseEntity<JsonNode> response = api.getWithHost("/orders", "www.app.test");

        assertThat(response.getStatusCode())
                .as("www.app.test must not silently become a tenant named www")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("feature 3 — the tenant arrives as a path segment")
    void pathSegmentResolver() {
        api.placeAs("initech", "Peter Gibbons", "Red stapler", 1999);

        ResponseEntity<JsonNode> response =
                api.getWithHeaders("/t/initech/orders", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).get("tenantId").asText()).isEqualTo("initech");
    }

    @Test
    @DisplayName("feature 6 — order is precedence: the header beats the path")
    void headerWinsOverPath() {
        api.placeAs("acme", "Road Runner", "Rocket skates", 12500);
        api.placeAs("globex", "Homer Simpson", "Hammock", 7500);

        ResponseEntity<JsonNode> response = api.getWithHeaders(
                "/t/globex/orders", api.tenantHeader("acme"));

        assertThat(response.getBody()).isNotEmpty();
        response.getBody().forEach(order ->
                assertThat(order.get("tenantId").asText())
                        .as("header is listed first in the chain, so it must win")
                        .isEqualTo("acme"));
    }

    @Test
    @DisplayName("feature 6 — falls through to the next resolver when the first has no opinion")
    void fallsThroughToPath() {
        api.placeAs("initech", "Milton Waddams", "Stapler", 2499);

        // No header, host carries no usable subdomain -> the path resolver answers.
        ResponseEntity<JsonNode> response =
                api.getWithHeaders("/t/initech/orders", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    @DisplayName("feature 7 — no resolver can answer, so the request is rejected")
    void strictModeRejectsUnresolvableRequests() {
        ResponseEntity<JsonNode> response =
                api.getWithHeaders("/orders", new HttpHeaders());

        assertThat(response.getStatusCode())
                .as("an empty list would read as 'no data' and hide the real problem")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
