package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.acme.orders.support.Api;
import com.acme.orders.support.OrdersDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Feature 83 — every log line emitted while serving a request carries the tenant.
 *
 * This is what makes a production incident tractable: "show me everything that happened
 * for acme" is a grep, not an investigation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.org.hibernate.SQL=DEBUG")
class LogEnrichmentTest {

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
    private ListAppender<ILoggingEvent> appender;
    private Logger sqlLogger;

    @BeforeEach
    void setUp() {
        OrdersDatabase.truncate();
        api = new Api(http);
        sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        sqlLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("SQL logged while serving acme is tagged tenant=acme")
    void logLinesCarryTheTenant() {
        api.placeAs("acme", "Wile E. Coyote", "Anvil", 4999);
        appender.list.clear();

        api.getAs("/orders", "acme");

        assertThat(appender.list)
                .as("no SQL was logged, so this test proves nothing")
                .isNotEmpty();
        assertThat(appender.list)
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry("tenant", "acme"));
    }

    @Test
    @DisplayName("the MDC follows the tenant, it is not stuck on the first one seen")
    void mdcFollowsTheTenantAcrossRequests() {
        api.placeAs("globex", "Hank Scorpio", "Doomsday device", 999999);
        appender.list.clear();

        api.getAs("/orders", "globex");

        assertThat(appender.list)
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry("tenant", "globex"));
    }
}
