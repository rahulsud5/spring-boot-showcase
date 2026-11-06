# 20 · Lessons learned while building and verifying this project

Problems hit during development and verification, with the cause and the fix, plus a few known
Spring Boot 4 changes that were handled up front (marked *known change*). Most are typical of a
Spring Boot 3 → 4 upgrade or of distributed-systems work in general.

## Build and platform (Spring Boot 4 / Framework 7)

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 1 | Maven Enforcer failed: banned `commons-logging` | Old habit of banning commons-logging. Spring Framework 7 removed `spring-jcl` and depends on **Apache Commons Logging 1.3** directly | Ban only `commons-logging:commons-logging:[,1.3)` (root `pom.xml`) |
| 2 | `spring.http.serviceclient.*` properties reported as unknown; `@ImportHttpServices` clients not configured | Boot 4 modularization: HTTP client auto-configuration lives in its own module | Add `spring-boot-starter-restclient` |
| 3 | Test compilation failed on slice annotations and `HealthIndicator` | Packages moved in Boot 4 (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `org.springframework.boot.health.contributor.*`, ...) | Update imports; use the per-technology test starters |
| 4 | Startup failure: `object is not an instance of declaring class` in a `@Bean` method | A `@Configuration` class with `@Bean` methods also implemented `AsyncConfigurer`. Boot 4 wraps `AsyncConfigurer` beans, so the proxied instance no longer matched the declaring class | Expose the `AsyncConfigurer` as a separate bean (`AsyncConfig`) |
| 5 | Validation annotations not found in notification-service | `spring-boot-starter-validation` isn't pulled in transitively any more | Declare it explicitly |
| 6 | Gateway tests didn't compile (`StepVerifier`) | `reactor-test` isn't part of every test starter | Add `io.projectreactor:reactor-test` (test scope) |
| 7 | *Known change:* the old `-Djarmode=layertools` extraction no longer works | The `layertools` jar mode was removed in Boot 4.1 | `java -Djarmode=tools -jar app.jar extract --layers` |
| 8 | *Known change:* `spring.data.mongodb.uri` is no longer the connection property | Boot 4 moved connection settings to `spring.mongodb.*` | `spring.mongodb.uri` |
| 9 | *Known change:* `spring.cloud.gateway.routes` no longer configures the WebFlux gateway | Spring Cloud Gateway 5 uses `spring.cloud.gateway.server.webflux.*` | Rename the prefix |

**General lesson:** after an upgrade, validate every property against the Boot configuration metadata
(the `spring-boot-properties-migrator` dependency logs renamed keys at startup). For this project a
script compared every key in every `application.yml` against the metadata of the resolved jars;
only the project's own `notification.*`/`app.*` keys and pass-through maps remained unknown.

## Java and Spring programming model

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 10 | `return` inside a `switch` *expression* didn't compile | Switch expressions must yield a value or throw | Use a switch *statement* (`OrderController.place`) |
| 11 | `@WebMvcTest` failed: no `AppProperties` bean | Test slices don't run `@ConfigurationPropertiesScan` | `@EnableConfigurationProperties(AppProperties.class)` in the test |
| 12 | Webhook test returned 404 | The slice only loaded `OrderController` | List both controllers in `@WebMvcTest({...})` |
| 13 | JPQL constructor expression failed for a nested record | Hibernate needs a resolvable, fully qualified class name | Make `StatusCount` a top-level record |
| 14 | Cached views containing `Money` must survive the Redis round trip | The default Redis cache serializer is JDK serialization, which requires `Serializable` on everything in the graph | `Money implements Serializable` (or switch to a JSON serializer) |
| 15 | ArchUnit reported **6 package cycles** | Application ↔ messaging, config ↔ application, persistence ↔ application dependencies | Introduce the `IntegrationEventPublisher` port, move `FraudScreeningRegistrar` to `application.fraud`, split metrics (`OrderMetrics` in application, `OutboxMetrics` as a `MeterBinder` in observability), move row → view mapping into the query service |

## Database

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 16 | Flyway **hung forever** on the `CREATE INDEX CONCURRENTLY` migration | Flyway holds a PostgreSQL advisory lock inside a transaction; `CONCURRENTLY` waits for all transactions that could see the table, including Flyway's own | `spring.flyway.postgresql.transactional-lock: false` + `executeInTransaction=false` in `V4__....sql.conf` |
| 17 | Hibernate `ddl-auto=validate` failed: expected `char(3)`, found `varchar` (or vice versa) | `columnDefinition` alone isn't enough for schema validation of `CHAR` columns | `@JdbcTypeCode(SqlTypes.CHAR)` + `length = 3` on currency/country |
| 18 | Hikari leak-detection warnings during the demo seed | The 50k-row repeatable migration legitimately holds a connection for a few seconds | Expected; leak detection threshold is for request paths, not bulk seeds |

## Distributed systems

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 19 | **The saga "hung"**: orders stayed PENDING; inventory never saw some OrderPlaced events | The topic was created with 3 partitions by an init script; then a `NewTopic` bean **increased it to 6** at startup. The already-running consumer was assigned only partitions 0-2, while some orders hashed to partition 5. It would only self-heal after the consumer's metadata refresh (`metadata.max.age.ms`, 5 min by default) | One owner for topic configuration, created *before* consumers start, with matching partition counts (`kafka-init` and beans both use 6). In production, manage topics with IaC (Terraform/Strimzi), and alert on partitions without an assigned consumer |
| 20 | Rate limiter seemed not to work for the first requests | Token bucket **burst capacity** (20) allows a burst before limiting at 10/s | Expected; verified 20 × 200 then 20 × 429 |
| 21 | HSTS header missing in local tests | Spring Security writes HSTS **only on secure (HTTPS) requests** | Expected; TLS terminates at the load balancer, which must forward `X-Forwarded-Proto` (`server.forward-headers-strategy`) |

## Process lessons

* **Verify against real infrastructure.** Unit tests and slices passed while the Flyway lock, the
  `CHAR` validation and the Kafka partition issue only appeared against real PostgreSQL and Kafka.
* **Measure, don't assume:** `EXPLAIN (ANALYZE, BUFFERS)` showed which indexes were used, and that
  seq scans on tiny tables are correct, not a problem.
* **Architecture tests pay off immediately:** they found real cycles that code review would likely have missed.
* **Keep the build reproducible offline:** proxy and mirror settings belong in `~/.m2/settings.xml`;
  CI caches `.m2`; Maven's resolver retries help with registry rate limits (HTTP 429).
