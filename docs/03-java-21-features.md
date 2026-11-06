# 03 · Java 21 features in a Spring codebase

Java 21 is an LTS release. These features change how idiomatic Spring code looks. Each row points to
real code in this repo.

| Feature | JEP | Where it's used | Why it helps |
|---|---|---|---|
| Records | 395 | DTOs (`OrderApiDtos`), events (`OrderEvent.OrderPlaced`), `Money`, `@ConfigurationProperties` (`AppProperties`), projections (`StatusCount`) | Immutable, `equals`/`hashCode`/`toString` for free, compact constructors validate input |
| Sealed classes and interfaces | 409 | `IntegrationEvent`, `OrderEvent`, `InventoryEvent`, `DiscountPolicy`, `InventoryPort.Availability` | Closed hierarchies: the compiler knows every subtype, so `switch` is checked for exhaustiveness |
| Pattern matching for `switch` | 441 | `InventoryEventHandler`, `DiscountPolicy.apply`, `OrderController` (idempotency outcome) | No `instanceof` chains, no forgotten case, no `default` hiding new subtypes |
| Record patterns | 440 | `case StockRejected(var id, var orderId, var reason, ...)` | Deconstruct and bind in one step |
| Virtual threads | 444 | `spring.threads.virtual.enabled=true`; pricing fan-out in `OrderApplicationService` | Blocking code scales like async code; Tomcat, `@Async`, `@Scheduled` and Kafka listeners all run on virtual threads |
| Sequenced collections | 431 | `List.getFirst()` / `getLast()` / `reversed()` in reports and tests | Uniform first/last access |
| `String` templates | ✗ | (Withdrawn in JDK 23, so not used) | |

## Records

```java
public record Money(BigDecimal amount, String currency) implements Serializable {
    public Money {                                   // compact constructor = invariant checks
        Objects.requireNonNull(amount);
        if (amount.scale() > 2) throw new IllegalArgumentException("max 2 decimals");
    }
    public Money plus(Money other) { requireSameCurrency(other); return new Money(amount.add(other.amount), currency); }
}
```

Records work as:
* **JPA embeddables** (Hibernate 6.2+): `Money` is `@Embeddable`. Records **cannot be entities**:
  entities need a no-arg constructor, mutability and proxies.
* **Configuration properties**, with constructor binding by default.
* **Jackson DTOs**, deserialized through the canonical constructor.
* **Spring Data projections**: `select new ...StatusCount(o.status, count(o))` in JPQL. The record must be
  top-level or static nested, and the JPQL needs its fully qualified name.

## Sealed types + exhaustive switch

```java
public sealed interface InventoryEvent extends IntegrationEvent
        permits StockReserved, StockRejected, StockReleased { ... }

switch (event) {                                     // no default: adding StockPartiallyReserved
    case StockReserved r -> orders.confirmFromSaga(r.orderId());      // breaks compilation here
    case StockRejected(var id, var orderId, var reason, var skus, var at) -> orders.rejectFromSaga(orderId, reason);
    case StockReleased r -> log.debug("compensation acknowledged {}", r.orderId());
}
```

The **event type registry** (`EventTypes`) uses `Class.getPermittedSubclasses()` to discover every event
type reflectively. A new event becomes serializable and deserializable automatically, and `EventTypesTest`
guards the result.

**Guarded patterns** (`when`) for business rules (`DiscountPolicy`):

```java
return switch (this) {
    case Percentage p when p.percent().signum() == 0 -> subtotal;
    case Percentage p   -> subtotal.minus(subtotal.times(p.percent()).divide(100));
    case FixedAmount f  -> subtotal.minus(f.amount()).atLeastZero();
    case NoDiscount n   -> subtotal;
};
```

This is the **Strategy pattern without a class per strategy and a Spring bean per strategy**. The
algorithm sits next to the data. Use it when the set of variants is closed and owned by you. Use
classic polymorphism (interface + beans) when third parties add variants.

## Virtual threads

One property switches the whole application over:

```yaml
spring.threads.virtual.enabled: true
```

Effects: Tomcat request handling, `@Async` (via `SimpleAsyncTaskExecutor` with virtual threads),
`@Scheduled`, Kafka and RabbitMQ listener containers run on virtual threads.

Explicit fan-out (`OrderApplicationService`):

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = skus.stream().map(sku -> executor.submit(() -> pricing.priceOf(sku))).toList();
    ...                                             // 10 blocking HTTP calls ≈ latency of the slowest one
}
```

**Rules and pitfalls:**
1. **Virtual threads don't make the database faster.** 10,000 virtual threads still share a Hikari pool
   of 20 connections. Limit concurrency against scarce resources with `@ConcurrencyLimit`
   (Spring 7) or a semaphore.
2. **Pinning:** before JDK 24, blocking inside `synchronized` pinned the carrier thread. JDK 24+
   (JEP 491) fixed most of this. On JDK 21, prefer `ReentrantLock` in hot paths and watch the
   `jdk.VirtualThreadPinned` JFR event.
3. **Don't pool virtual threads.** They're cheap; create one per task.
4. `ThreadLocal`s work, but with millions of threads, big thread-locals cost memory. Scoped values
   are the future replacement (finalized in JDK 25).
5. CPU-bound work doesn't gain anything. Virtual threads help with waiting, not computing.

## Structured concurrency (preview in 21)

`StructuredTaskScope` is still in preview in 21 (changed again in 25), so production code here uses
`ExecutorService` + `try-with-resources`. The idea is the same: subtasks can't outlive their parent scope.

## Other everyday features

* `var` for local variables with obvious types (not for public APIs).
* Text blocks for SQL in `OrderReportingRepository` and JSON in tests.
* `Stream.toList()` (16) instead of `collect(Collectors.toList())`, which returns an unmodifiable list.
* Switch expressions with `->` and `yield`. (A `return` inside a switch *expression* doesn't compile.
  See [lessons learned](20-lessons-learned.md).)
* Helpful NullPointerExceptions (14+), which name the null variable in the message.
