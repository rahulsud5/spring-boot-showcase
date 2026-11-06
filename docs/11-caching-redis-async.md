# 11 · Caching, Redis and async processing

## 1. Spring Cache abstraction

`@Cacheable` / `@CachePut` / `@CacheEvict` are provider-neutral (AOP proxies, same self-invocation
caveat as `@Transactional`). The `CacheManager` decides where entries live. Here it's Redis
(`CacheConfig`):

```java
RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(5))
        .disableCachingNullValues()                       // a "not found" shouldn't stick for the TTL
        .prefixCacheNameWith("order-service:v1::");       // bump v1 → v2 when the cached class changes
RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaults)
        .withInitialCacheConfigurations(Map.of(
                "prices", defaults.entryTtl(Duration.ofSeconds(30)),
                "dailySales", defaults.entryTtl(Duration.ofMinutes(10))))
        .transactionAware()                               // put/evict only after the DB commit
        .build();
```

| Cache | Key | TTL | Why |
|---|---|---|---|
| `prices` | `#sku` | 30 s | Hot, rarely changing; avoids an HTTP call per order line |
| `dailySales` | `#from + '-' + #to` | 10 min | Expensive window-function report |

**Why Redis instead of Caffeine (in-memory)?** Shared across all instances (consistent evictions,
no cold start after a deploy). The cost is a network hop (~0.5 ms) and serialization. A common
combination is a **two-level cache**: a small Caffeine L1 with a short TTL in front of Redis.

## 2. Caching patterns

| Pattern | How | Trade-off |
|---|---|---|
| **Cache-aside** (used here) | Read: cache → miss → DB → put. Write: DB, then evict | Simple; brief staleness possible |
| Read-through | The cache loads on miss itself | Cleaner callers; needs cache-side loaders |
| Write-through | Write to cache and DB synchronously | Consistent; slower writes |
| Write-behind | Write to cache, flush to DB asynchronously | Fast; risk of loss |
| Refresh-ahead | Refresh hot keys before expiry | No miss latency for hot keys |

**Cache problems and fixes:**
* **Stampede** (many concurrent misses for one hot key): `@Cacheable(sync = true)` (one loader per
  key per instance), TTL jitter, refresh-ahead.
* **Penetration** (requests for keys that never exist): cache negative results briefly, or use a Bloom filter.
* **Avalanche** (many keys expire at once): randomize TTLs.
* **Inconsistency:** evict *after* commit (`transactionAware()`), keep TTLs short for data that changes,
  and never cache authorization decisions longer than the token lifetime.
* **Serialization changes:** version the key prefix; prefer JSON for cross-language/cross-version tolerance.

## 3. Redis beyond caching (used in this repo)

| Use | Redis feature | Where |
|---|---|---|
| Idempotency keys | `SET key value NX EX 86400` (atomic "set if absent" with expiry) | `IdempotencyService` |
| Rate limiting | Token bucket implemented in a Lua script (atomic) | Gateway `RedisRateLimiter` |
| Cache | Strings with TTL | `CacheConfig` |

Other common uses: distributed locks (Redisson, or `SET NX PX` + fencing tokens; be careful),
sessions (Spring Session), leaderboards (sorted sets), pub/sub and streams, counters.

**Idempotency flow** (`IdempotencyService`, a sealed `Outcome`):
```
SET idem:order-service:{customer}:{key} IN_PROGRESS NX EX 24h
  ├─ acquired        → Started    → process → SET value = orderId (Completed)
  ├─ value = orderId → Completed  → return 200 with the existing order (replay)
  └─ IN_PROGRESS     → InProgress → 409 (the first request is still running)
on failure → DEL the key (abandon), so the client may retry with the same key
```
Keys are scoped per customer, so one user can't collide with (or probe) another user's keys.

## 4. Async processing

**`@Async`** (`@EnableAsync` in `AsyncConfig`):
* With `spring.threads.virtual.enabled=true`, Boot's `applicationTaskExecutor` uses **virtual threads**.
* Return `void`, or `CompletableFuture<T>` to let callers compose results.
* Exceptions in `void` methods are invisible unless an `AsyncUncaughtExceptionHandler` logs them
  (configured in `AsyncConfig`).
* The security context and MDC aren't propagated automatically. Use `DelegatingSecurityContextExecutor`
  / a `TaskDecorator`. Micrometer context propagation handles trace ids.
* Same proxy caveat: calling an `@Async` method on `this` runs it synchronously.

**Where async appears in this repo:**

| Mechanism | Example | Guarantee |
|---|---|---|
| `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` | `NotificationPublisher` sends the RabbitMQ command after commit, off the request thread | Best effort (lost if the process dies right after commit) |
| `@Scheduled(fixedDelayString = ...)` | `OutboxRelay` polls every 500 ms | At-least-once (rows stay until published) |
| Virtual-thread fan-out | Parallel price lookups in `OrderApplicationService` | Synchronous result, concurrent execution |
| Message brokers | Kafka saga, RabbitMQ notifications, SQS returns | Durable, retried |

**Scheduling in a cluster:** `@Scheduled` runs on *every* instance. The outbox relay is safe because
`FOR UPDATE SKIP LOCKED` divides the work. For "exactly one instance" jobs, use **ShedLock** or a
Kubernetes `CronJob`.

**Choosing:** if losing the work is acceptable → `@Async`. If not → persist it first (outbox table,
message broker) and process it asynchronously from there.
