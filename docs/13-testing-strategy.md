# 13 · Testing strategy

## 1. The test pyramid in this repo

```
              ▲  few, slow, realistic
             ╱ ╲     E2E: docker compose + http/requests.http (manual/CI smoke)
            ╱   ╲    OrderFlowIT: full app + Postgres/Kafka/RabbitMQ/Redis containers
           ╱     ╲   OrderRepositoryIT, StockServiceIT: one adapter + a real database
          ╱───────╲  Slice tests: @WebMvcTest, @GraphQlTest, gRPC in-process
         ╱         ╲ Unit tests: domain, handlers, filters (no Spring)
        ╱───────────╲ Architecture tests: ArchUnit rules (no runtime at all)
              many, fast, focused
```

| Level | Classes | Spring context | Speed | Runs in |
|---|---|---|---|---|
| Unit | `OrderTest`, `DiscountPolicyTest`, `InventoryEventHandlerTest`, `NotificationListenerTest`, `CorrelationIdGlobalFilterTest`, `EventTypesTest` | None | ms | `mvn test` (Surefire) |
| Architecture | `ArchitectureTest` (7 rules) | None (bytecode analysis) | < 1 s | `mvn test` |
| Web slice | `OrderControllerTest`, `CatalogControllerTest` | MVC + security only | ~1 s | `mvn test` |
| GraphQL slice | `OrderGraphQlControllerTest` (`@GraphQlTest`) | GraphQL only | ~1 s | `mvn test` |
| gRPC | `InventoryGrpcServiceTest` (service called directly with a recording `StreamObserver`) | None | ms | `mvn test` |
| Auth server | `AuthServerTest` (`@SpringBootTest` + MockMvc) | Full, no external deps | seconds | `mvn test` |
| Persistence IT | `OrderRepositoryIT` (`@DataJpaTest` + Postgres), `StockServiceIT` (Mongo replica set) | JPA / Mongo slice | seconds | `mvn verify` (Failsafe, needs Docker) |
| Full IT | `OrderFlowIT` (`@SpringBootTest` + `TestcontainersConfiguration`) | Everything | 10+ s | `mvn verify` (needs Docker) |

**Naming convention:** `*Test` → Surefire (`test` phase), `*IT` → Failsafe (`integration-test` +
`verify` phases). `mvn verify -DskipITs` (or `-Pfast`) skips containers when Docker isn't available.

## 2. Unit tests: test behavior, not wiring

```java
@Test
void cancellingAShippedOrderFails() {
    Order order = newOrder(new DiscountPolicy.None(), new LineDraft("SKU-1", 1, Money.of("1", "EUR")));
    order.confirm();
    order.ship();
    assertThatThrownBy(() -> order.cancel("changed my mind"))
            .isInstanceOf(IllegalOrderStateException.class)
            .hasMessageContaining("SHIPPED");
}
```
Domain objects are plain Java: no Spring and no mocks needed. That's a direct benefit of the hexagonal
architecture ([docs/14](14-design-patterns.md)). Use a fixed `Clock` for anything time-related.

## 3. Slice tests (Boot 4 packages!)

```java
@WebMvcTest({OrderController.class, PartnerWebhookController.class})  // org.springframework.boot.webmvc.test.autoconfigure
@Import(SecurityConfig.class)                                           // the real security rules
@EnableConfigurationProperties(AppProperties.class)                     // slices skip @ConfigurationPropertiesScan
class OrderControllerTest {
    @Autowired MockMvcTester mvc;                                       // AssertJ-style MockMvc (Spring 6.2+)
    @MockitoBean OrderApplicationService orders;                        // replaces Boot's deprecated @MockBean
    ...
    assertThat(mvc.post().uri("/api/orders").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.write")))
            .header("Idempotency-Key", "key-12345").contentType(APPLICATION_JSON).content(body))
        .hasStatus(HttpStatus.CREATED).headers().containsHeader("Location");
}
```

* A slice loads only one layer, so it's fast and failures point at that layer.
* **Test the security rules with the real `SecurityConfig`**: 401 / 403 / 2xx per endpoint.
* `@MockitoBean` creates a new context per distinct mock set. Keep mock sets consistent across
  tests to benefit from **context caching**.

## 4. Integration tests with Testcontainers

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    @Bean @ServiceConnection PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:17-alpine"); }
    @Bean @ServiceConnection KafkaContainer kafka()         { return new KafkaContainer("apache/kafka:4.0.0"); }
    @Bean @ServiceConnection RabbitMQContainer rabbit()     { ... }
    @Bean @ServiceConnection(name = "redis") GenericContainer<?> redis() { ... }
}
```

* `@ServiceConnection` turns containers into connection details. No `@DynamicPropertySource` needed.
* **Same images as production** (Postgres 17, not H2): H2 doesn't support `jsonb`, partial/GIN
  indexes, `SKIP LOCKED`, window-function edge cases, Flyway's Postgres-specific migrations...
* **Testcontainers 2.x** (Boot 4): artifacts are `testcontainers-postgresql`, `-kafka`, ...; classes
  moved to `org.testcontainers.postgresql.PostgreSQLContainer` etc.; JUnit 4 support was removed.
* **Dev-time reuse:** `TestOrderServiceApplication.main()` starts the app with the same containers
  (`SpringApplication.from(OrderServiceApplication::main).with(TestcontainersConfiguration.class).run(args)`).
* Async assertions: **Awaitility** (`await().atMost(Duration.ofSeconds(20)).untilAsserted(...)`) for the saga in
  `OrderFlowIT`. Never `Thread.sleep`.
* CI: GitLab runs them with Docker-in-Docker ([docs/18](18-gitlab-ci.md)).

## 5. Architecture tests (ArchUnit)

Rules in `ArchitectureTest` keep the design from eroding:
1. The domain depends on nothing (no Spring web, persistence adapters, messaging).
2. Nothing outside `web`/`security` depends on `web`.
3. The application layer reaches integrations **only through ports**.
4. **No package cycles.** This rule found 6 real cycles during development (fixed by introducing the
   `IntegrationEventPublisher` port and moving classes; see [lessons learned](20-lessons-learned.md)).
5. No field injection (`@Autowired` on fields).
6. Controllers are never `@Transactional`.
7. `@Service` classes live in `application` or `messaging`.

## 6. Other techniques worth knowing

| Technique | Tool | When |
|---|---|---|
| Consumer-driven contract tests | Spring Cloud Contract, Pact | Many teams consuming one API |
| Mutation testing | PIT (`pitest-maven`) | Measure test *quality*, not just coverage |
| Load testing | Gatling, k6 | Before peak events; validate SLOs and pool sizes |
| Property-based testing | jqwik | Money/discount math, parsers |
| Chaos | Toxiproxy, Chaos Mesh | Resilience verification |
| Snapshot/approval tests | ApprovalTests | Large JSON/GraphQL responses |
| Security tests | `spring-security-test`, OWASP ZAP baseline scan in CI | AuthN/AuthZ rules, headers |

## 7. Coverage

JaCoCo runs on every build; the `ci` profile enforces **≥ 70 % line coverage** per module
(`jacoco:check`). GitLab shows it in merge requests (`coverage_report` with the JaCoCo XML).
Coverage is a floor, not a goal: 100 % coverage with no assertions proves nothing. Mutation
testing tells you whether tests actually catch bugs.

## 8. What was verified for this repo

* `mvn verify -DskipITs`: **all 7 modules build, 59 tests pass** (unit, slice, architecture, auth server).
* The `*IT` classes compile, but couldn't run in the build sandbox (no Docker daemon). The same flows
  were verified manually against real PostgreSQL 17, MongoDB 8 (replica set), Kafka 4, Redis and
  RabbitMQ processes: saga, idempotency, compensation, gRPC, GraphQL, security, gateway.
