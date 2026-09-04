package com.acme.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The original shape of this service, kept working.
 *
 * <h2>Why this file exists</h2>
 *
 * Adding {@code spring-boot-starter-security} — needed for the JWT resolver and membership
 * verification — switched on Spring Security's default filter chain for every profile,
 * which means HTTP Basic on every endpoint. That is the right default for Spring and the
 * wrong one for a service deliberately deployed behind a gateway that terminates
 * authentication and sets the tenant header. Without this chain, adding the dependency
 * turns every existing caller's request into a 401, with nothing in the diff that looks
 * like it did that.
 *
 * <p>"Permit all" here says only that <em>this service</em> does not authenticate. It says
 * nothing about tenant isolation, which row-level security still enforces on the
 * connection either way — a request that reaches the database with no tenant reads
 * nothing.
 */
@Configuration
@ConditionalOnProperty(name = "orders.security.enabled", havingValue = "false",
        matchIfMissing = true)
public class BehindAGatewayConfig {

    @Bean
    SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
