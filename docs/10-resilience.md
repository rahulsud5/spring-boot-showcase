# 10 · Resilience: Resilience4j, Spring 7 retries, timeouts, bulkheads

In a distributed system **something is always failing**. Resilience patterns keep a local failure from
becoming a cascading outage.

## 1. The toolbox, and where each tool is used

| Pattern | Problem it solves | Implementation | Location |
|---|---|---|---|
| **Timeout** | Waiting forever ties up threads and connections | gRPC deadline 800 ms; HTTP client connect/read timeouts; Hikari `connection-timeout`; lock timeout 3 s | `GrpcInventoryAdapter`, `spring.http.serviceclient.catalog.*`, `OrderRepository.findByIdForUpdate` |
| **Retry + back-off + jitter** | Transient failures (network blips, 503, lock conflicts) | Spring 7 `@Retryable` | `CatalogPricingAdapter.priceOf`, `OrderApplicationService.ship` |
| **Circuit breaker** | Hammering a dependency that is down; slow calls piling up | Resilience4j via Spring Cloud CircuitBreaker | `GrpcInventoryAdapter` (`inventory`), gateway route `orders` |
| **Fallback / graceful degradation** | Users get an error instead of a reduced result | `Availability.Unknown` → accept as PENDING; gateway `forward:/fallback/orders` → 503 Problem Detail | `GrpcInventoryAdapter`, `FallbackController` |
| **Bulkhead / concurrency limit** | One slow dependency exhausting all resources | Spring 7 `@ConcurrencyLimit(20)` | `CatalogPricingAdapter` |
| **Rate limiting** | Abuse, traffic spikes | Gateway `RequestRateLimiter` (Redis token bucket, 10/s, burst 20) | `api-gateway` |
| **Idempotency** | Retries causing duplicates | Idempotency-Key, dedupe tables | [docs/07](07-messaging-kafka-vs-rabbitmq.md), [docs/08](08-rest-grpc-graphql.md) |
| **Async decoupling** | Temporal coupling | Outbox + Kafka saga | [docs/00](00-architecture-and-system-design.md) |
| **Dead-letter queues** | Poison messages blocking a partition/queue | Kafka DLT, RabbitMQ DLX | `KafkaConfig`, `RabbitTopologyConfig` |
| **Load shedding at startup/shutdown** | Traffic hitting a pod that isn't ready or is stopping | Readiness probe, graceful shutdown, `preStop` sleep | `deploy/k8s/base` |

## 2. Spring Framework 7 resilience (no extra library)

```java
@Configuration @EnableResilientMethods class ResilienceConfig { ... }

@Cacheable(cacheNames = "prices", key = "#sku")
@Retryable(includes = {ResourceAccessException.class, HttpServerErrorException.class},
           maxRetries = 3, delay = 100, multiplier = 2, jitter = 50, maxDelay = 1_000)
@ConcurrencyLimit(20)
public Money priceOf(String sku) { ... }
```

* Delays: 100 ms → 200 ms → 400 ms (±50 ms jitter), capped at 1 s.
* **Retry only transient, idempotent operations.** 4xx responses are excluded (a 404 won't fix itself).
  `ship()` retries `ObjectOptimisticLockingFailureException`: each attempt re-reads the latest version
  in a **new** transaction. That's why `@Retryable` sits on `OrderApplicationService` and
  `@Transactional` on `OrderCommandService` (different beans, so the retry wraps the transaction,
  not the other way round).
* **Jitter** avoids the thundering herd: without it, all clients retry at the same moments.
* `@Retryable` also works on reactive return types (it retries the `Mono`/`Flux` subscription).
* For programmatic use: `RetryTemplate` (core, Spring 7).

**Spring 7 built-ins vs Resilience4j**

| | Spring 7 core | Resilience4j |
|---|---|---|
| Retry | ✓ `@Retryable`, `RetryTemplate` | ✓ |
| Concurrency limit / bulkhead | ✓ `@ConcurrencyLimit` (blocking wait) | ✓ semaphore and thread-pool bulkheads |
| Circuit breaker | ✗ | ✓ (rate- and slow-call-based) |
| Rate limiter, time limiter | ✗ | ✓ |
| Metrics | Via observation | Rich Micrometer metrics per instance |

Use the Spring built-ins for the simple cases and Resilience4j when you need state (circuit breaking).

## 3. Circuit breaker (Resilience4j)

```
          failure rate ≥ 50% or slow-call rate ≥ 50% (over the last 20 calls, min 10)
 CLOSED ──────────────────────────────────────────────────────────────▶ OPEN
   ▲                                                                     │ wait 10 s
   │ 3 trial calls succeed                                               ▼
   └────────────────────────────── HALF_OPEN ◀──────────────────────────┘
                                       │ a trial call fails → OPEN again
```

Configured in `ResilienceConfig` as the default for every breaker created by `Resilience4JCircuitBreakerFactory`:

* **COUNT_BASED** window of 20 calls, `minimumNumberOfCalls(10)`: no tripping on the first failure after startup.
* `slowCallDurationThreshold(500 ms)` + `slowCallRateThreshold(50)`: slow calls are usually the first symptom.
* `TimeLimiter` of 1 s around each call.
* `automaticTransitionFromOpenToHalfOpenEnabled(true)`.

Usage (vendor-neutral Spring Cloud CircuitBreaker API):

```java
return circuitBreakers.create("inventory").run(
        () -> callInventoryOverGrpc(lines),               // protected call
        failure -> new Availability.Unknown(failure.getMessage()));   // fallback: degrade, don't fail
```

**Choosing a fallback:** a cached or default value, a degraded answer (here: "unknown, accept and
verify asynchronously"), or a fast, clear error (gateway: 503 with `Retry-After`). Never a fallback that
silently lies (e.g. "in stock" when you don't know).

Observability: `resilience4j_circuitbreaker_state`, `..._calls_seconds`, `..._failure_rate` metrics;
alert when a breaker stays open.

## 4. Gateway resilience (api-gateway `application.yml`)

```yaml
filters:
  - name: RequestRateLimiter        # 10 req/s per user, burst 20 → 429
    args: { redis-rate-limiter.replenishRate: 10, redis-rate-limiter.burstCapacity: 20 }
  - name: CircuitBreaker
    args: { name: orders, fallbackUri: forward:/fallback/orders, statusCodes: 500, 502, 503, 504 }
  - name: Retry                     # ONLY idempotent GETs. Never blindly retry POST (duplicate orders!)
    args: { retries: 2, methods: GET, series: SERVER_ERROR }
```

Verified: 20 rapid requests → 20 × 200 (burst), then 20 × 429; with order-service stopped, the
fallback answered 503 in about 1.3 s.

## 5. Retry budgets and amplification

Retries at every layer multiply: gateway 3 attempts × service 4 attempts × client 3 = 36 calls to a
struggling dependency. Rules:

1. **Retry at one layer**, ideally closest to the failure, with a total time budget.
2. Timeouts shrink downstream: gateway 3 s > service call 1 s > gRPC deadline 800 ms.
3. Circuit breakers stop retries once failure is systemic.
4. Propagate deadlines (gRPC does this natively).

## 6. Testing resilience

* Unit tests with fakes that throw (e.g. `InventoryPort` returning `Unknown`).
* Integration: stop a container mid-test (Testcontainers), or use **Toxiproxy** to inject latency.
* Chaos engineering in staging: kill pods, add network latency (AWS FIS, Chaos Mesh) and watch SLOs.
