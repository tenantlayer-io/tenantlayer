package com.acme.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication only. Notice what is absent: no rule anywhere says which tenant a caller
 * may act as.
 *
 * <p>That question is answered by TenantLayer — the tenant is resolved from the token's
 * claim (feature 4) and the caller's right to it is verified (feature 52) — so this class
 * never grows a per-tenant authorisation rule, and no controller has to remember to apply
 * one.
 *
 * <p>Active only under the {@code secure} profile. The unsecured shape lives in
 * {@link BehindAGatewayConfig}.
 */
@Configuration
@ConditionalOnProperty(name = "orders.security.enabled", havingValue = "true")
public class SecurityConfig {

    @Bean
    SecurityFilterChain securedFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))
                .build();
    }
}
