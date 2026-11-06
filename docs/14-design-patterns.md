# 14 · Design patterns (GoF, enterprise, microservice) in this codebase

A pattern is worth using when it solves a problem you actually have. Each entry names the problem.

## 1. Architectural style: hexagonal (ports & adapters)

```
                 ┌──────────────── order-service ────────────────┐
  REST ─────────▶│ web (adapter in)                              │
  GraphQL ──────▶│ graphql (adapter in)        application       │──▶ PricingPort  ──▶ integration.catalog (REST)
  Kafka ────────▶│ messaging.kafka (in)    ┌──────────────────┐  │──▶ InventoryPort ─▶ integration.inventory (gRPC)
                 │                         │ use cases +      │  │──▶ IntegrationEventPublisher ─▶ messaging.outbox
                 │                         │ ports            │  │──▶ FraudScreening ─▶ application.fraud
                 │                         └────────┬─────────┘  │
                 │                                  ▼            │
                 │                         domain (pure Java)    │──▶ persistence (JPA, JdbcClient)
                 └───────────────────────────────────────────────┘
```

* **Problem:** business logic tangled with frameworks is hard to test and change.
* **Solution:** the domain and application layers define *ports* (interfaces); adapters implement them.
  Dependencies point inward. ArchUnit enforces it ([docs/13](13-testing-strategy.md)).
* **Pragmatism:** JPA annotations stay on domain entities (a full separate persistence model doubles
  the code for little gain here). Spring Data repositories are used directly as the persistence port.

## 2. GoF patterns

| Pattern | Problem | Where |
|---|---|---|
| **Strategy** | Interchangeable algorithms | `DiscountPolicy` (sealed records + switch); `FraudScreening` (Lenient/Strict implementations) |
| **Factory method** | Controlled creation with invariants | `Order.place(...)`, `Money.of(...)`, `EventEnvelope.wrap(...)` |
| **Builder** | Many optional parameters | `RedisCacheConfiguration`, `TopicBuilder`, `CircuitBreakerConfig.custom()`, `JsonMapper.builder()` |
| **Proxy** | Cross-cutting behavior without touching code | Every `@Transactional`, `@Cacheable`, `@Retryable`, `@Async`, `@PreAuthorize`; `@HttpExchange` clients |
| **Decorator** | Add behavior around a call | `CatalogPricingAdapter` (cache + retry + limit around `CatalogClient`); gateway filters |
| **Chain of responsibility** | Ordered processing with early exit | Security filter chain; gateway `GlobalFilter`s; servlet filters (`CorrelationIdFilter`) |
| **Template method** | Fixed algorithm with variable steps | `OncePerRequestFilter` (`ApiKeyAuthenticationFilter.doFilterInternal`), `JdbcClient`, `TransactionTemplate`, `KafkaTemplate` |
| **Observer** | React to events without coupling | `@TransactionalEventListener` on `OrderStatusChanged`; `ApplicationEvent`s |
| **Adapter** | Translate between models | `GrpcInventoryAdapter` (protobuf ↔ domain), `CatalogPricingAdapter` |
| **Facade** | Simple entry point over subsystems | `OrderApplicationService` (pricing, fraud, inventory, commands); `ReservationFacade` |
| **State** | Behavior depends on state | `OrderStatus` transitions guarded in `Order` (`confirm`, `cancel`, `ship`) |
| **Specification** | Composable query predicates | `OrderSpecifications` (`hasStatus`, `belongsTo`, `createdBetween`... combined with `allOf`) |
| **Registry** | Look up types by name | `EventTypes` (built from `getPermittedSubclasses()`) |
| **Singleton** | One shared instance | Spring singleton beans (container-managed, not the static GoF variant) |

## 3. Domain-Driven Design building blocks

| Concept | Example |
|---|---|
| Aggregate root | `Order` (owns `OrderLine`s; all changes go through its methods; `@Version` guards it) |
| Value object | `Money`, `Address` (records, immutable, equality by value) |
| Domain event | `OrderStatusChanged` (via `AbstractAggregateRoot.registerEvent`) |
| Integration event | `OrderEvent.OrderPlaced` (public contract in `contracts`, versioned) |
| Repository | `OrderRepository` (collection-like access to aggregates) |
| Bounded context | One per service (Ordering, Inventory, Notification, Identity) |
| Anti-corruption layer | `GrpcInventoryAdapter` maps protobuf types so they never leak into the domain |

**Domain event vs integration event:** domain events are in-process, fine-grained and may change
freely; integration events are a published contract, coarse-grained, versioned, and must evolve compatibly.

## 4. Microservice and data patterns

| Pattern | Problem | Where |
|---|---|---|
| **API Gateway** | One entry point: routing, auth, rate limits, CORS | `api-gateway` |
| **Database per service** | Independent deploys and schemas | Postgres (orders), Mongo (inventory) |
| **Saga (choreography)** | Distributed transaction without 2PC | OrderPlaced → StockReserved/Rejected → Confirmed/Cancelled |
| **Transactional outbox** | Atomic DB write + event publish | `OutboxWriter`, `OutboxRelay` ([ADR 0001](adr/0001-transactional-outbox.md)) |
| **Idempotent consumer** | At-least-once delivery duplicates | `processed_messages`, reservation `_id = orderId` |
| **Compensating transaction** | Undo a completed step | `OrderCancelled` → `StockReleased` |
| **CQRS (lite)** | Different read and write needs | `OrderCommandService` vs `OrderQueryService` + `OrderReportingRepository` + materialized view |
| **Circuit breaker / bulkhead / retry** | Failure isolation | [docs/10](10-resilience.md) |
| **Backend for frontend / strangler fig** | Migrate legacy APIs | Gateway `legacy-catalog` route rewrites old paths and adds `Deprecation` headers |
| **Correlation id / distributed tracing** | Follow a request across services | `CorrelationIdGlobalFilter`, `CorrelationIdFilter`, OpenTelemetry |
| **Health check API** | Orchestrator decisions | Actuator probes |
| **Externalized configuration** | Same artifact in every environment | Profiles, config trees, Secrets Manager |
| **Sidecar / service mesh** | mTLS, retries outside the app | Not used (mentioned as an alternative for gRPC load balancing) |

## 5. Spring-specific patterns

* **`BeanRegistrar` (Spring 7)** for conditional, programmatic registration: `FraudScreeningRegistrar`
  chooses `StrictFraudScreening` or `LenientFraudScreening` from a property, AOT-friendly.
* **Customizer beans** to tweak auto-configuration instead of replacing it (`Customizer<Resilience4JCircuitBreakerFactory>`).
* **`@ConfigurationProperties` records** as typed configuration objects.
* **Composed annotations**: `@Sku` (Bean Validation) bundles `@NotBlank` + `@Pattern`.
* **Sealed result types** instead of exceptions for expected outcomes: `IdempotencyService.Outcome`,
  `InventoryPort.Availability`, `StockService.ReservationResult`.

## 6. Anti-patterns avoided (and why)

| Anti-pattern | Why it hurts | What we do instead |
|---|---|---|
| Shared database between services | Hidden coupling, lockstep deploys | Events + own stores |
| Distributed monolith (sync chains everywhere) | Availability = product of all services | Async saga, degrade on failure |
| Anemic domain model | Rules scattered across services | Behavior on `Order` (`cancel`, `confirm`, `ship`) |
| God service | Untestable, merge conflicts | Small focused services: command, query, application facade |
| Field injection, `new`-ing collaborators | Hidden dependencies, untestable | Constructor injection (ArchUnit) |
| Catch-and-ignore | Silent data loss | `GlobalExceptionHandler`, DLTs, `AsyncUncaughtExceptionHandler` |
| Trusting client prices | Fraud | Prices always resolved server-side (`PricingPort`) |
