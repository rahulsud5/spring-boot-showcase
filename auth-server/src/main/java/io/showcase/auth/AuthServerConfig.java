package io.showcase.auth;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Users + token customization for the authorization server.
 *
 * <h2>OAuth2 flows used in this project</h2>
 * <ul>
 *   <li><b>Authorization Code + PKCE</b> (client "shop-web"): browser apps. The user logs in HERE,
 *       never in the app. PKCE (a code_verifier/code_challenge pair) stops a stolen authorization code
 *       from being redeemed. OAuth 2.1 makes PKCE mandatory and removes the Implicit and Password grants.</li>
 *   <li><b>Client Credentials</b> (client "machine-client"): service-to-service or batch jobs with no
 *       user. The client authenticates with its own secret.</li>
 *   <li><b>Refresh tokens</b>: short-lived access tokens (5 min) + longer-lived refresh tokens, so a stolen
 *       access token is useful only briefly.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class AuthServerConfig {

    /**
     * DelegatingPasswordEncoder: stored hashes carry their algorithm prefix ({@code {bcrypt}$2a$10$...}),
     * so you can migrate algorithms (bcrypt → argon2) gradually. NEVER store plain or merely SHA-hashed
     * passwords: use a slow, salted, adaptive hash.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Demo users (in memory). Production: a database (JdbcUserDetailsManager), LDAP, or federation to
     * a corporate IdP. The username becomes the JWT "sub" claim, which order-service uses as the
     * customer id.
     */
    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password(encoder.encode("alice-password")).roles("CUSTOMER").build(),
                User.withUsername("bob").password(encoder.encode("bob-password")).roles("CUSTOMER", "ADMIN").build(),
                User.withUsername("ops").password(encoder.encode("ops-password")).roles("OPS").build());
    }

    /**
     * TOKEN CUSTOMIZER: shapes the JWT claims of ACCESS tokens.
     * <ul>
     *   <li>{@code roles}: the user's roles without the "ROLE_" prefix. Resource servers map them back
     *       to ROLE_* authorities (see order-service SecurityConfig).</li>
     *   <li>{@code aud}: the APIs this token is meant for. Each resource server only accepts tokens
     *       listing it (spring.security.oauth2.resourceserver.jwt.audiences), so a token stolen from one
     *       API can't be replayed against another.</li>
     * </ul>
     * Keep tokens SMALL: they travel on every request. Don't put permissions lists or profile data in them.
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> accessTokenCustomizer() {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            context.getClaims().audience(List.of("order-service", "inventory-service"));

            if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                context.getClaims().claim("roles", List.of("SERVICE"));
                return;
            }
            Set<String> roles = context.getPrincipal().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring("ROLE_".length()))
                    .collect(Collectors.toSet());
            context.getClaims().claim("roles", roles);
        };
    }
}
