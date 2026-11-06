# 09 · Security: OAuth2, JWT, filters, roles, OWASP

## 1. The players

```
 ┌──────────┐  1. login + PKCE (authorization_code)      ┌──────────────┐
 │ shop-web │ ─────────────────────────────────────────▶ │ auth-server  │  Spring Authorization Server
 │ (browser)│ ◀──────────── 2. access token (JWT) ────── │  :9000       │  (part of Spring Security 7)
 └────┬─────┘                                            └──────┬───────┘
      │ 3. Authorization: Bearer <JWT>                          │ JWKS (public keys)
      ▼                                                         │ /oauth2/jwks
 ┌────────────┐ 4. validates the JWT (signature, exp, iss) ◀───┤
 │ api-gateway│ ── relays the token ──▶ ┌───────────────┐ ◀────┘ 5. validates AGAIN (+ audience),
 └────────────┘                         │ order-service │        maps scopes + roles → authorities
                                        └───────────────┘
 machine-client ── client_credentials ──▶ auth-server ──▶ JWT with roles [SERVICE]
 payment partner ── X-API-Key ──▶ order-service /api/webhooks/** (ApiKeyAuthenticationFilter)
```

* **auth-server** (Authorization Server): issues tokens. Clients: `shop-web` (public client,
  authorization code + **PKCE**, no secret) and `machine-client` (`client_credentials` for service-to-service).
* **api-gateway** and **order-service** are **resource servers**: they *validate* tokens with the
  issuer's public keys (`spring.security.oauth2.resourceserver.jwt.issuer-uri`). No call to the
  auth-server per request; keys are cached from `/oauth2/jwks`.
* **Defense in depth:** the gateway rejects anonymous traffic early, and each service validates again,
  because internal traffic must not be trusted blindly (zero trust).

## 2. JWT anatomy (what our token customizer produces)

```json
{
  "iss": "http://localhost:9000",
  "sub": "alice",
  "aud": ["shop-web", "order-service", "inventory-service"],
  "scope": ["openid", "orders.read", "orders.write"],
  "roles": ["CUSTOMER"],
  "iat": 1762400000, "exp": 1762400300
}
```

Validation checklist (Spring does all of these): **signature** (RS256 against the JWKS), `exp`/`nbf` with
clock skew, `iss` equals the configured issuer, **`aud` contains this API** (`audiences: order-service`,
which prevents token replay against other services).

**JWT pitfalls:** never accept `alg: none` or let the token choose the algorithm; keep access tokens
short-lived (5 min for `shop-web`, 10 min for `machine-client` here) and use refresh tokens for sessions; JWTs can't be revoked before `exp`
(use short TTLs, or opaque tokens + introspection when revocation is critical); never put secrets or PII
in claims (they're only base64-encoded, not encrypted); browsers: keep tokens out of `localStorage`
(prefer a BFF with an HttpOnly session cookie).

## 3. Roles vs scopes vs authorities

| Concept | Answers | Source | Spring authority |
|---|---|---|---|
| **Scope** | What may this *client application* do on the user's behalf? | `scope` claim (OAuth2 consent) | `SCOPE_orders.read` |
| **Role** | What may this *user* do in the business? | Custom `roles` claim | `ROLE_ADMIN` |
| Authority | Spring's generic permission string | Both, via `jwtAuthenticationConverter` | |

`hasRole("ADMIN")` checks the authority `ROLE_ADMIN`; `hasAuthority("SCOPE_orders.write")` checks a
scope. Real rules combine them, e.g. "client has `orders.write` AND user owns the order".

**Demo users** (auth-server): `alice` = CUSTOMER, `bob` = CUSTOMER + ADMIN, `ops` = OPS. Passwords are in
`auth-server/README.md` (demo only).

## 4. The security filter chain (order-service `SecurityConfig`)

Every request passes through `DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain`:

```
CorrelationIdFilter (servlet filter, runs first: sets the MDC id)
 └─ SecurityFilterChain
    DisableEncodeUrlFilter
    SecurityContextHolderFilter
    HeaderWriterFilter            → CSP, HSTS, X-Frame-Options DENY, Referrer-Policy, X-Content-Type-Options
    CorsFilter                    → answers preflight requests
    LogoutFilter
    ApiKeyAuthenticationFilter    ← CUSTOM, added before BearerTokenAuthenticationFilter
    BearerTokenAuthenticationFilter → JwtAuthenticationProvider (decode, validate, convert)
    RequestCacheAwareFilter / SecurityContextHolderAwareRequestFilter
    AnonymousAuthenticationFilter
    SessionManagementFilter       → STATELESS: no HttpSession is ever created
    ExceptionTranslationFilter    → AuthenticationException → 401, AccessDeniedException → 403
    AuthorizationFilter           → the requestMatchers rules below
 └─ DispatcherServlet → @PreAuthorize / @PostAuthorize (method security, AOP)
```

Authorization rules, **most specific first, deny by default**:

```java
.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
.requestMatchers("/actuator/**").hasRole("OPS")
.requestMatchers(HttpMethod.POST, "/api/webhooks/**").hasRole("PARTNER")
.requestMatchers(HttpMethod.GET,  "/api/orders/**").hasAuthority("SCOPE_orders.read")
.requestMatchers(HttpMethod.POST, "/api/orders/**").hasAuthority("SCOPE_orders.write")
.requestMatchers("/graphql").authenticated()
.anyRequest().denyAll()          // a new endpoint is closed until someone opens it deliberately
```

**Method security** handles object-level rules the URL can't express:
* `@PostAuthorize("hasRole('ADMIN') or returnObject.customerId() == authentication.name")` on
  `OrderQueryService.get`: **prevents IDOR / BOLA** (reading someone else's order by guessing its id).
* `@PreAuthorize("hasRole('ADMIN')")` on shipping and reports.

**Custom filter (`ApiKeyAuthenticationFilter`):**
* Hashes the presented key with SHA-256 and compares it to the configured hash with
  `MessageDigest.isEqual` (**constant-time**, so timing doesn't leak how much of the key matched).
  The plain key is never stored.
* Applies only to `/api/webhooks/**` (`shouldNotFilter`).
* **Not a `@Component`**: Boot would otherwise *also* register it as a servlet filter for every request.
  It's created with `new` inside the security configuration.

**CSRF** is disabled because the API is stateless and authenticated by a header, which browsers
don't attach automatically. Any cookie-based UI must keep CSRF protection on.

## 5. Gateway security (`GatewaySecurityConfig`, WebFlux)

Reactive equivalent: `ServerHttpSecurity`, `.oauth2ResourceServer(jwt)`, authorize exchanges, the
fallback endpoint is public, actuator health is public. Token relay: the gateway forwards the
`Authorization` header unchanged to downstream services.

## 6. OWASP Top 10 (2025) mapping

| # | Risk | Mitigation in this repo |
|---|---|---|
| A01 | Broken Access Control | Deny-by-default rules, `@PostAuthorize` ownership checks (IDOR), audience validation, CORS allow-list, actuator restricted to OPS |
| A02 | Security Misconfiguration | Security headers, no stack traces in errors (`server.error.include-*: never`), GraphiQL/Swagger off in production, non-root container, read-only root filesystem in Kubernetes |
| A03 | Software Supply Chain Failures | Maven Enforcer (banned dependencies: Log4j 1.x, JUnit 4, ancient commons-logging), OWASP dependency-check profile, GitLab Dependency Scanning, Trivy image scan, SBOM endpoint (`/actuator/sbom`), pinned base images |
| A04 | Cryptographic Failures | TLS at the edge, HSTS, RS256-signed JWTs, DelegatingPasswordEncoder (bcrypt), secrets from files/Secrets Manager (never in Git), SHA-256 API key hashes |
| A05 | Injection | Bind parameters everywhere (JPA, `JdbcClient` named params, Mongo `Criteria`), no string-concatenated SQL, Bean Validation on input (`@Sku` pattern), CSP header |
| A06 | Insecure Design | Threat-modelled flows: idempotency keys, rate limiting, saga state machine, least privilege per service (IRSA roles) |
| A07 | Authentication Failures | Delegated to the OAuth2 server; PKCE for public clients; short token TTLs; no custom password handling in services |
| A08 | Software or Data Integrity Failures | Signed tokens, event envelopes with type registry (no polymorphic deserialization of arbitrary classes), CI pipeline with protected branches and manual production gate |
| A09 | Logging & Alerting Failures | Structured logs with trace ids, audit log table (`REQUIRES_NEW`), DLQ alerts via SNS, PII masking in notification logs |
| A10 | Mishandling of Exceptional Conditions | `GlobalExceptionHandler` maps every failure to a deliberate Problem Detail; fail closed (deny on error); timeouts and circuit breakers |

Other measures: **rate limiting** at the gateway (Redis token bucket, per user), request size limits,
**HSTS only takes effect over HTTPS** (Spring writes it only on secure requests, which is why local
HTTP tests don't show it), and the Boot 4.1 HTTP-client SSRF filter for outbound calls to user-supplied URLs.

## 7. Testing security

* `@WebMvcTest` + `@Import(SecurityConfig.class)` + `jwt()` post-processor from
  `spring-security-test`: `OrderControllerTest` covers 401 (no token), 403 (missing write scope), 201,
  idempotent replay, Problem Details for invalid input, and API version 2.
* Webhook test: only a valid partner API key is accepted (202); other keys are rejected.
* `AuthServerTest`: the discovery document and JWKS are served; `client_credentials` returns a token
  with roles, audience and scopes; a wrong client secret is rejected.
* Verified end to end: 401 without a token, 403 for alice on an admin endpoint, 401 for a tampered
  token, 400 for an unsupported API version.
