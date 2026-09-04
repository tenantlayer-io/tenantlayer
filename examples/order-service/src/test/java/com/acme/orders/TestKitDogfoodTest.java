package com.acme.orders;

import static io.tenantlayer.test.IsolationAssertions.assertTenantCannotSee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orders.support.OrdersDatabase;
import io.tenantlayer.test.IsolationAssertions;
import io.tenantlayer.test.WithTenant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Features 104 and 106, used the way a customer would use them.
 *
 * Everything else in this project drives the service over HTTP. This exercises the
 * TESTING KIT the library ships — the part a buyer writes their own assertions with —
 * from outside the library, which nothing else does.
 */
@SpringBootTest
class TestKitDogfoodTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        OrdersDatabase.bindDataSource(registry);
    }

    @BeforeAll
    static void prepare() {
        OrdersDatabase.reset();
    }

    @Autowired
    private DataSource applicationDataSource;

    @Autowired
    private JdbcTemplate jdbc;

    /** Seeds on the privileged connection: the application connection is subject to the
     *  policy, so it could only ever insert rows for the tenant currently in context. */
    private JdbcTemplate seeder;

    @BeforeEach
    void setUp() {
        OrdersDatabase.truncate();
        seeder = new JdbcTemplate(OrdersDatabase.privileged());
        IsolationAssertions.bind(applicationDataSource, OrdersDatabase.privileged());
        IsolationAssertions.bindTable("orders", "tenant_id");
        seed("acme", "Anvil");
        seed("globex", "Doomsday device");
        seed("globex", "Hammock");
    }

    @Test
    @WithTenant("acme")
    @DisplayName("@WithTenant scopes plain repository access, no HTTP involved")
    void withTenantScopesDirectDatabaseAccess() {
        Long visible = jdbc.queryForObject("select count(*) from orders", Long.class);

        assertThat(visible)
                .as("@WithTenant should have put acme on the connection")
                .isEqualTo(1);
    }

    @Test
    @WithTenant("acme")
    @DisplayName("assertTenantCannotSee passes when isolation holds")
    void isolationAssertionPasses() {
        assertTenantCannotSee("globex");
    }

    @Test
    @DisplayName("the kit refuses to give a false pass when no tenant is set")
    void assertionRefusesToRunWithoutATenant() {
        assertThatThrownBy(() -> assertTenantCannotSee("globex"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no tenant in context");
    }

    @Test
    @WithTenant("acme")
    @DisplayName("the kit refuses to give a false pass when the other tenant has no rows")
    void assertionRefusesWhenTheOtherTenantHasNoData() {
        assertThatThrownBy(() -> assertTenantCannotSee("nobody-has-ever-ordered"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("would pass whether or not isolation works");
    }

    private void seed(String tenant, String item) {
        seeder.update("""
                insert into orders (tenant_id, customer, item, amount_cents, status)
                values (?, ?, ?, ?, 'PLACED')""", tenant, tenant + " customer", item, 1000);
    }
}
