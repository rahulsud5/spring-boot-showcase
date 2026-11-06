# api-gateway (port 8080)

**Spring Cloud Gateway 5** (WebFlux server variant): the single public entry point.

| Feature | Where | Guide |
|---|---|---|
| Routes `orders` (`/api/orders/**`, `/graphql` → order-service) and `catalog` (`/api/products/**` → inventory-service) | `application.yml` | [08](../docs/08-rest-grpc-graphql.md) |
| Rate limiting: Redis token bucket per user, 10 req/s, burst 20 → 429 | `application.yml`, `GatewayConfig.userKeyResolver` | [10](../docs/10-resilience.md) |
| Circuit breaker + fallback (503 Problem Detail), retries for GET only | `application.yml`, `FallbackController` | [10](../docs/10-resilience.md) |
| Java DSL route: `/legacy/catalog/**` rewritten to `/api/products/**` with a `Deprecation` header (strangler fig) | `GatewayConfig` | [14](../docs/14-design-patterns.md) |
| JWT validation at the edge (resource server, reactive) | `GatewaySecurityConfig` | [09](../docs/09-security-oauth2-jwt-owasp.md) |
| Correlation id generation/propagation | `CorrelationIdGlobalFilter` | [12](../docs/12-observability-and-production-readiness.md) |
| CORS for the browser app | `application.yml` (`globalcors`) | [09](../docs/09-security-oauth2-jwt-owasp.md) |

Note the Gateway 5 property prefix: `spring.cloud.gateway.server.webflux.*`.

## Run

```bash
docker compose up -d                       # Redis is needed for rate limiting
mvn -pl api-gateway -am spring-boot:run
```
Then call the APIs through http://localhost:8080 (see `http/requests.http`).
