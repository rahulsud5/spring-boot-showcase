package io.showcase.order.security;

import io.showcase.order.config.AppProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * SPRING SECURITY configuration: this service is an OAuth2 RESOURCE SERVER that accepts JWT bearer tokens.
 *
 * <h2>The filter chain (simplified, in order)</h2>
 * <pre>
 *  request ─► CorrelationIdFilter (servlet filter, runs before security)
 *          ─► DelegatingFilterProxy ─► FilterChainProxy ─► SecurityFilterChain:
 *               DisableEncodeUrlFilter, SecurityContextHolderFilter, HeaderWriterFilter (security headers),
 *               CorsFilter, LogoutFilter,
 *               ApiKeyAuthenticationFilter   ◄── our custom filter, added before the bearer filter
 *               BearerTokenAuthenticationFilter (Authorization: Bearer → JwtDecoder → JwtAuthenticationToken)
 *               RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,
 *               ExceptionTranslationFilter (AuthenticationException → 401, AccessDeniedException → 403)
 *               AuthorizationFilter          ◄── authorizeHttpRequests rules below
 *          ─► DispatcherServlet ─► @PreAuthorize/@PostAuthorize (method security, via AOP)
 * </pre>
 * Start with {@code logging.level.org.springframework.security=TRACE} to watch a request walk through it.
 *
 * <h2>JWT validation (auto-configured from spring.security.oauth2.resourceserver.jwt.*)</h2>
 * The signature is verified against the auth server's public keys (JWKS, fetched and cached from
 * issuer-uri), then {@code exp/nbf} with clock skew, {@code iss} == the configured issuer, and
 * {@code aud} contains our audience. Only then is the token trusted. No call to the auth server per
 * request: that's why JWTs scale, and also why they can't be revoked before expiry (so keep them
 * short-lived: 5-15 minutes).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity   // activates @PreAuthorize / @PostAuthorize / @PreFilter / @PostFilter
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AppProperties properties) throws Exception {
        http
            // CSRF protection defends cookie-based sessions. A stateless API authenticated by an
            // Authorization header isn't vulnerable (browsers don't attach that header automatically),
            // so disabling it here is correct. Keep CSRF ON for any session or cookie-based UI!
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(Customizer.withDefaults())

            // SECURITY HEADERS (OWASP Secure Headers project)
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .frameOptions(frame -> frame.deny()))

            // URL AUTHORIZATION: first match wins, most specific first. "DENY BY DEFAULT" at the end means a
            // new endpoint is never accidentally public (OWASP A01: Broken Access Control).
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/actuator/**").hasRole("OPS")
                .requestMatchers(HttpMethod.POST, "/api/webhooks/**").hasRole("PARTNER")
                .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAuthority("SCOPE_orders.read")
                .requestMatchers(HttpMethod.POST, "/api/orders/**").hasAuthority("SCOPE_orders.write")
                .requestMatchers("/graphql").authenticated()
                .anyRequest().denyAll())

            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))

            // A CUSTOM FILTER placed relative to a built-in one
            .addFilterBefore(new ApiKeyAuthenticationFilter(properties.security().partnerApiKeySha256()),
                             BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Maps JWT claims to Spring Security AUTHORITIES:
     * <ul>
     *   <li>{@code scope: "orders.read orders.write"} → SCOPE_orders.read, SCOPE_orders.write
     *       (what the CLIENT application may do: OAuth2 consent)</li>
     *   <li>{@code roles: ["ADMIN"]} → ROLE_ADMIN (what the USER may do: the business role)</li>
     * </ul>
     * Scopes and roles answer different questions; real authorization usually needs both.
     * {@code hasRole('ADMIN')} checks for the authority "ROLE_ADMIN" (the prefix is added automatically).
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var scopes = new JwtGrantedAuthoritiesConverter();               // default: "scope"/"scp" claim → SCOPE_*
        Converter<Jwt, Collection<GrantedAuthority>> combined = jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
            return authorities;
        };
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(combined);
        converter.setPrincipalClaimName("sub");                          // authentication.getName() = the subject
        return converter;
    }

    /**
     * CORS: browsers block cross-origin XHR/fetch unless the server allows it. List EXACT origins.
     * {@code allowedOrigins("*")} together with credentials is rejected by browsers and is a security
     * smell. In this architecture the gateway is the public entry point, so CORS matters mostly there;
     * it's kept here for direct access during development.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3001", "https://shop.showcase.example"));
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-API-Version", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("Location", "X-Correlation-Id"));
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
