package io.showcase.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OAuth2 AUTHORIZATION SERVER. Clients and token settings are configured as properties in
 * application.yml (Spring Boot auto-configuration); this module only adds users, a password encoder
 * and a token customizer (AuthServerConfig).
 *
 * <p>Endpoints (all advertised by /.well-known/openid-configuration):
 * <ul>
 *   <li>{@code /oauth2/authorize}: browser login + consent (authorization code flow with PKCE)</li>
 *   <li>{@code /oauth2/token}: exchanges a code, refresh token or client credentials for tokens</li>
 *   <li>{@code /oauth2/jwks}: the PUBLIC keys resource servers use to verify JWT signatures</li>
 *   <li>{@code /oauth2/introspect}, {@code /oauth2/revoke}, {@code /userinfo}</li>
 * </ul>
 */
@SpringBootApplication
public class AuthServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServerApplication.class, args);
    }
}
