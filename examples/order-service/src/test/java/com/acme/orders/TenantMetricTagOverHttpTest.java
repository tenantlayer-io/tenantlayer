package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.tenantlayer.metrics.TenantTagLimiter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Feature 82 — the tenant tag, observed from a real service over real HTTP.
 *
 * <p>The library's own tests call the filter directly. This one makes actual requests and
 * reads the tag off the observations Spring produced, which is the only way to know the
 * filter is wired into the observation pipeline at all rather than merely constructible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantMetricTagOverHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("orders")
            .withUsername("admin")
            .withPassword("admin_pwd");

    private static final String APP_USER = "orders_app";
    private static final String APP_PASSWORD = "orders_pwd";

    /** Two tenants fit; anyone after that must be folded into the overflow value. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("tenantlayer.metrics.max-tenants", () -> "2");
    }

    /** Records the tenant tag of every observation the application produces. */
    static final Map<String, Integer> TAGS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class CaptureObservations {
        @Bean
        ObservationHandler<Observation.Context> tagCapture() {
            return new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                    KeyValue tenant = context.getLowCardinalityKeyValue("tenant");
                    if (tenant != null) {
                        TAGS.merge(tenant.getValue(), 1, Integer::sum);
                    }
                }
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
            statement.execute("grant select on tenantlayer_tenants to " + APP_USER);
            statement.execute("grant usage, select on all sequences in schema public to " + APP_USER);
        }
    }

    @BeforeEach
    void clearTags() {
        TAGS.clear();
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("each tenant's requests are tagged with that tenant")
    void requestsAreTaggedWithTheActingTenant() {
        get("/orders", "acme");
        get("/orders", "globex");

        assertThat(TAGS.keySet())
                .as("the tenant never reached the observation")
                .contains("acme", "globex");
    }

    @Test
    @DisplayName("tenants beyond the cap are folded together rather than each getting a series")
    void tenantsBeyondTheCapShareOneTag() {
        get("/orders", "acme");
        get("/orders", "globex");     // cap of 2 is now full
        get("/orders", "initech");
        get("/orders", "umbrella");

        assertThat(TAGS).containsKey(TenantTagLimiter.DEFAULT_OVERFLOW);
        assertThat(TAGS.keySet())
                .as("a third and fourth tenant must not each create their own time series")
                .doesNotContain("initech", "umbrella");
    }

    @Test
    @DisplayName("a request with no tenant is tagged as none, not as overflow")
    void untenantedRequestsAreDistinguishable() {
        http.exchange("/orders", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(TAGS)
                .as("no tenant and over-the-cap must be tellable apart in a dashboard")
                .containsKey(TenantTagLimiter.NONE);
    }

    private void get(String path, String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", tenant);
        http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
