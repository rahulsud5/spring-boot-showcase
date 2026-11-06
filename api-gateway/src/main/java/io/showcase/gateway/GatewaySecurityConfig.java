package io.showcase.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * REACTIVE Spring Security: the same concepts as the servlet version (order-service SecurityConfig), with
 * a {@code SecurityWebFilterChain} and {@code ServerHttpSecurity} instead.
 *
 * <p>DEFENSE IN DEPTH: the gateway validates the JWT (rejecting garbage early), AND each service validates
 * it again. Never assume "it passed the gateway, so it's trusted": internal traffic can bypass the
 * gateway (zero-trust networking). The original Authorization header is forwarded unchanged, so
 * downstream services see the same token.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)              // stateless bearer-token API
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/products/**", "/legacy/catalog/**").permitAll()  // public catalog
                        .pathMatchers("/api/**", "/graphql").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
