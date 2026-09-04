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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Feature 10 — the tenant survives into an @Async worker, over real HTTP.
 *
 * /orders/summary is computed on a worker thread that queries the database itself. If the
 * tenant fails to cross that boundary the endpoint returns zeros rather than the caller's
 * figures, so the assertion is on the numbers, not on a context lookup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncPropagationOverHttpTest {

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
    @DisplayName("a summary computed on a worker thread is scoped to the calling tenant")
    void summaryIsScopedOnTheWorkerThread() {
        api.placeAs("acme", "Wile E. Coyote", "Anvil", 4999);
        api.placeAs("acme", "Road Runner", "Rocket skates", 12500);
        api.placeAs("globex", "Hank Scorpio", "Doomsday device", 999999);

        JsonNode acme = api.getAs("/orders/summary", "acme").getBody();
        assertThat(acme.get("orderCount").asLong())
                .as("zero would mean the tenant never reached the worker thread")
                .isEqualTo(2);
        assertThat(acme.get("totalCents").asLong())
                .as("globex's 999999 must not be in acme's total")
                .isEqualTo(17499);

        JsonNode globex = api.getAs("/orders/summary", "globex").getBody();
        assertThat(globex.get("orderCount").asLong()).isEqualTo(1);
        assertThat(globex.get("totalCents").asLong()).isEqualTo(999999);
    }

    @Test
    @DisplayName("a reused worker thread does not serve the previous caller's tenant")
    void repeatedCallsDoNotBleedAcrossTenants() {
        api.placeAs("acme", "Acme", "Anvil", 100);
        api.placeAs("globex", "Globex", "Device", 200);
        api.placeAs("globex", "Globex", "Hammock", 300);

        // Alternate tenants so the same worker threads are reused between calls.
        for (int i = 0; i < 4; i++) {
            assertThat(api.getAs("/orders/summary", "acme").getBody().get("totalCents").asLong())
                    .isEqualTo(100);
            assertThat(api.getAs("/orders/summary", "globex").getBody().get("totalCents").asLong())
                    .isEqualTo(500);
        }
    }
}
