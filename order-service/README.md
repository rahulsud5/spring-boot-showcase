# order-service (port 8081)

The flagship service: most Spring features live here.

| Area | Where to look | Guide |
|---|---|---|
| Domain model (aggregate, value objects, sealed strategies, JSpecify) | `domain/` | [03](../docs/03-java-21-features.md), [14](../docs/14-design-patterns.md) |
| Use cases, transactions, locking, method security, caching | `application/` | [04](../docs/04-transactions.md) |
| Ports (inventory, pricing, events, fraud) + `BeanRegistrar` | `application/port`, `application/fraud` | [14](../docs/14-design-patterns.md) |
| JPA repositories, specifications, keyset scrolling, 13 SQL reports | `persistence/` | [05](../docs/05-database-indexes-and-queries.md) |
| Flyway migrations with commented indexes; 50k-row demo data | `src/main/resources/db` | [05](../docs/05-database-indexes-and-queries.md) |
| Outbox, Kafka consumer + DLT, RabbitMQ publisher | `messaging/` | [07](../docs/07-messaging-kafka-vs-rabbitmq.md) |
| gRPC client + circuit breaker; HTTP interface client + `@Retryable` | `integration/` | [08](../docs/08-rest-grpc-graphql.md), [10](../docs/10-resilience.md) |
| REST (API versioning, idempotency, Problem Details) | `web/` | [08](../docs/08-rest-grpc-graphql.md) |
| GraphQL (`@BatchMapping`) | `graphql/`, `resources/graphql` | [08](../docs/08-rest-grpc-graphql.md) |
| Security filter chain, JWT roles + scopes, API-key filter | `security/` | [09](../docs/09-security-oauth2-jwt-owasp.md) |
| Custom health, endpoint, metrics | `observability/` | [12](../docs/12-observability-and-production-readiness.md) |

## Run

```bash
# from the repo root: infrastructure first
docker compose up -d
mvn -pl order-service -am spring-boot:run -Dspring-boot.run.profiles=demo   # demo = 50k seeded orders
# or with Testcontainers instead of Compose:
mvn -pl order-service -am test-compile && run TestOrderServiceApplication from the IDE
```

Swagger UI: http://localhost:8081/swagger-ui.html · GraphiQL: http://localhost:8081/graphiql ·
Health: http://localhost:8081/actuator/health

## Profiles

| Profile | Effect |
|---|---|
| (default) | Local development against `localhost` infrastructure; Docker Compose support enabled |
| `demo` | Loads `db/demo/R__demo_data.sql` (50k orders) for the reporting queries and EXPLAIN plans |
| `docker` | Container hostnames, JSON (ECS) logs |
| `kubernetes` | Production-like: 10 % trace sampling, GraphiQL off, Compose support off |
| `cds-training` | Used only during the Docker image build to create the CDS archive (no external connections) |

## Tests

```bash
mvn -pl order-service -am verify -DskipITs   # unit, slice and architecture tests (no Docker)
mvn -pl order-service -am verify             # + OrderRepositoryIT and OrderFlowIT (Docker needed)
```
