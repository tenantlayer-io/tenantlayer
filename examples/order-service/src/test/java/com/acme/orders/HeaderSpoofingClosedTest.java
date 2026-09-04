package com.acme.orders;

import static com.acme.orders.support.FakeTokens.bearer;
import static com.acme.orders.support.FakeTokens.tokenFor;
import static com.acme.orders.support.FakeTokens.tokenWithoutTenantClaim;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orders.support.Api;
import com.acme.orders.support.FakeTokens;
import com.acme.orders.support.OrdersDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gap this project's README used to end on:
 *
 * <blockquote>"The header resolver trusts {@code X-Tenant-ID}. Anyone who can reach the
 * service can set it. That is acceptable behind a gateway that overwrites the header, and
 * unacceptable otherwise. Feature 52 (membership verification) and the JWT claim resolver
 * are what close it."</blockquote>
 *
 * <p>This is that test. Two independent defences are exercised, because they fail
 * differently and a deployment might only have one:
 *
 * <ul>
 *   <li><strong>Precedence.</strong> The signed claim outranks the header, so a spoofed
 *       header is not consulted at all and the caller silently gets their own tenant.</li>
 *   <li><strong>Membership.</strong> When the header <em>is</em> the resolved source, the
 *       claimed tenant is checked against the token and a mismatch is refused outright.</li>
 * </ul>
 *
 * <p>Note what did not change to achieve this: no controller, no repository, no entity.
 * The service still contains no tenancy code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secure")
@Import(FakeTokens.class)
class HeaderSpoofingClosedTest {

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
        placeAs(tokenFor("acme"), "acme laptop");
        placeAs(tokenFor("globex"), "globex laptop");
        placeAs(tokenFor("globex"), "globex monitor");
    }

    @Test
    @DisplayName("a token for acme cannot reach globex by setting the header")
    void spoofedHeaderIsIgnoredInFavourOfTheSignedClaim() {
        HttpHeaders headers = bearer(tokenFor("acme"));
        headers.set("X-Tenant-ID", "globex");

        ResponseEntity<JsonNode> response = api.getWithHeaders("/orders", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).get("tenantId").asText())
                .as("the header said globex; the token said acme, and the token wins")
                .isEqualTo("acme");
    }

    @Test
    @DisplayName("a header-resolved tenant the token does not cover is refused with 403")
    void headerResolvedTenantIsCheckedAgainstTheToken() {
        // This token carries no tenant_id claim, so the JWT resolver abstains and the
        // header becomes the resolved source — which is exactly when membership matters.
        HttpHeaders headers = bearer(tokenWithoutTenantClaim("acme"));
        headers.set("X-Tenant-ID", "globex");

        ResponseEntity<JsonNode> response = api.getWithHeaders("/orders", headers);

        assertThat(response.getStatusCode())
                .as("acme's bearer asked for globex and was told no")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the same request for its own tenant succeeds")
    void headerResolvedTenantWithinTheTokenIsAllowed() {
        HttpHeaders headers = bearer(tokenWithoutTenantClaim("acme"));
        headers.set("X-Tenant-ID", "acme");

        ResponseEntity<JsonNode> response = api.getWithHeaders("/orders", headers);

        assertThat(response.getStatusCode())
                .as("without this, the 403 above could be rejecting everything")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("a bearer for two tenants may act as either, and only those")
    void multiTenantBearer() {
        String token = tokenWithoutTenantClaim("acme", "globex");

        HttpHeaders asAcme = bearer(token);
        asAcme.set("X-Tenant-ID", "acme");
        assertThat(api.getWithHeaders("/orders", asAcme).getBody()).hasSize(1);

        HttpHeaders asGlobex = bearer(token);
        asGlobex.set("X-Tenant-ID", "globex");
        assertThat(api.getWithHeaders("/orders", asGlobex).getBody()).hasSize(2);

        HttpHeaders asUmbrella = bearer(token);
        asUmbrella.set("X-Tenant-ID", "umbrella");
        assertThat(api.getWithHeaders("/orders", asUmbrella).getStatusCode())
                .as("membership is a list, not a wildcard")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("no token at all is rejected before any tenant is resolved")
    void unauthenticatedIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", "acme");

        assertThat(api.getWithHeaders("/orders", headers).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void placeAs(String token, String item) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String body = """
                {"customer":"c","item":"%s","amountCents":1000}""".formatted(item);
        ResponseEntity<JsonNode> response = http.exchange("/orders",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
