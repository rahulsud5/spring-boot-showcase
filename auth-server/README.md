# auth-server (port 9000)

An OAuth2 / OpenID Connect **authorization server** built with Spring Authorization Server, which is
part of **Spring Security 7** (starter: `spring-boot-starter-security-oauth2-authorization-server`).

## Clients and users (demo only)

| Client | Grant | Notes |
|---|---|---|
| `shop-web` | `authorization_code` + **PKCE** | Public client (no secret); redirect `http://localhost:3001/callback`; access token 5 min, refresh 8 h |
| `machine-client` | `client_credentials` | Secret `machine-secret`; token carries `roles: [SERVICE]`; 10 min |

| User | Password | Roles |
|---|---|---|
| alice | `alice-password` | CUSTOMER |
| bob | `bob-password` | CUSTOMER, ADMIN |
| ops | `ops-password` | OPS |

**Never use these in a real environment.**

## What it shows

* Clients configured via properties (`spring.security.oauth2.authorizationserver.client.*`).
* `DelegatingPasswordEncoder` (`{bcrypt}` prefixed hashes, upgradeable algorithms).
* A token customizer (`AuthServerConfig`) that adds an `aud` claim for the resource servers and a
  `roles` claim (user roles, or `SERVICE` for machine clients).
* Endpoints: `/.well-known/openid-configuration`, `/oauth2/jwks`, `/oauth2/token`, `/oauth2/authorize`.

```bash
curl -s -u machine-client:machine-secret -d grant_type=client_credentials \
     -d scope="orders.read orders.write" http://localhost:9000/oauth2/token
```

## Production notes

* **Signing keys:** by default a new RSA key pair is generated at every startup, so tokens become invalid
  after a restart and replicas disagree. Provide a `JWKSource` bean backed by a keystore or KMS, and
  rotate keys (publish the new key in the JWKS before signing with it).
* **Persistence:** use `JdbcRegisteredClientRepository` and `JdbcOAuth2AuthorizationService` (or Redis)
  so several replicas share state.
* Consider a managed identity provider (Amazon Cognito, Okta, Auth0, Keycloak) unless you need full control.
