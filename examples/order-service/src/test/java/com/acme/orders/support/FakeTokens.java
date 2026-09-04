package com.acme.orders.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * A decoder that trusts a token because the test wrote it.
 *
 * <p>The point of these tests is what TenantLayer does with a <em>validated</em> token, not
 * whether Spring Security validates signatures — it does, and re-proving that here would
 * mean standing up an authorisation server for no gain. So the token format is
 * {@code claim=value;claim=value}, and a comma makes a list.
 */
@TestConfiguration
public class FakeTokens {

    /**
     * Encoded, because a bearer token may only contain the characters RFC 6750 allows
     * ({@code [A-Za-z0-9-._~+/]} plus trailing {@code =}). Spring Security's
     * BearerTokenResolver checks that before any decoder is consulted, so a readable
     * {@code claim=value;claim=value} string is rejected with a 401 that looks like a
     * decoder problem and is not one.
     */
    private static String encode(String claims) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decode(String token) {
        return new String(java.util.Base64.getUrlDecoder().decode(token),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A token asserting the tenant it acts as, and the tenants it is entitled to. */
    public static String tokenFor(String tenantId, String... memberOf) {
        List<String> parts = new ArrayList<>();
        if (tenantId != null) {
            parts.add("tenant_id=" + tenantId);
        }
        String[] tenants = memberOf.length == 0 && tenantId != null
                ? new String[] {tenantId} : memberOf;
        if (tenants.length > 0) {
            parts.add("tenants=" + String.join(",", tenants));
        }
        return encode(String.join(";", parts));
    }

    /** A token that says nothing about which tenant to act as, only what it may reach. */
    public static String tokenWithoutTenantClaim(String... memberOf) {
        return encode("tenants=" + String.join(",", memberOf));
    }

    public static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            Map<String, Object> claims = new java.util.LinkedHashMap<>();
            claims.put("sub", "test-user");
            for (String pair : decode(token).split(";")) {
                if (pair.isBlank()) {
                    continue;
                }
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                claims.put(kv[0], kv[1].contains(",")
                        ? Arrays.asList(kv[1].split(","))
                        : kv[1]);
            }
            return new Jwt(token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"), claims);
        };
    }
}
