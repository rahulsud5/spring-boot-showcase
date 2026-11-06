# 04 · Spring transactions in depth

## 1. How `@Transactional` works

`@Transactional` is implemented with an **AOP proxy**. Callers get a proxy that opens the transaction,
calls your method, then commits, or rolls back on an exception.

```
caller ──▶ [proxy: TransactionInterceptor] ──▶ OrderCommandService.place()
               │ begin (get connection, autocommit=false, bind to thread)
               │ ... method runs, repositories reuse the bound connection ...
               └ commit / rollback, release the connection
```

Consequences of the proxy design (the classic bugs):

| Pitfall | Why | Fix |
|---|---|---|
| **Self-invocation**: `this.otherTransactionalMethod()` ignores its annotation | The call doesn't pass through the proxy | Move the method to another bean (this repo: `AuditService` is separate *because* it uses `REQUIRES_NEW`), or use `TransactionTemplate` |
| `private` / `final` methods | Can't be proxied | Make them public and non-final |
| Checked exceptions **don't** roll back by default | EJB heritage: only `RuntimeException` and `Error` do | `rollbackFor = Exception.class`, or throw unchecked exceptions (this repo) |
| Catching the exception inside the method | The proxy never sees it, so it commits | Rethrow, or `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` |
| Long transactions around remote calls | Connection held while waiting on the network | **Remote calls outside the transaction** (see §4) |
| `UnexpectedRollbackException` | Inner `REQUIRED` method marked the shared transaction rollback-only, outer method tried to commit | `REQUIRES_NEW` for independent work, or let the exception propagate |

## 2. Propagation, all used in this repo

| Propagation | Meaning | Example |
|---|---|---|
| `REQUIRED` (default) | Join the current transaction or start one | `OrderCommandService.place` |
| `REQUIRES_NEW` | Suspend the caller's transaction, run in a new one, commit independently | `AuditService.record`: the audit row survives even if the business transaction rolls back |
| `MANDATORY` | Fail if there's no transaction | `OutboxWriter.publish`: an outbox row **must** commit with the business change, so calling it outside a transaction is a bug and fails loudly |
| `SUPPORTS` | Use a transaction if present, otherwise run without | Rarely useful; read helpers |
| `NOT_SUPPORTED` | Suspend any transaction | Long-running exports that must not hold locks |
| `NEVER` | Fail if a transaction exists | Guard methods that do remote I/O |
| `NESTED` | Savepoint inside the current transaction (JDBC only) | Partial rollback of a batch item |

## 3. Isolation and anomalies

| Level | Dirty read | Non-repeatable read | Phantom | Lost update (PG) | Postgres implementation |
|---|---|---|---|---|---|
| READ COMMITTED (PG default) | ✗ | ✓ possible | ✓ possible | ✓ possible | New snapshot per statement |
| REPEATABLE READ | ✗ | ✗ | ✗ (in PG) | detected → serialization error | Snapshot per transaction |
| SERIALIZABLE | ✗ | ✗ | ✗ | ✗ | SSI: may abort with 40001, so **retry** |

This repo stays on READ COMMITTED and prevents lost updates explicitly with locking, which is
easier to reason about than relying on isolation levels.

## 4. Locking strategies

**Optimistic locking (`@Version`)**, used by `Order` for `ship()`:
```sql
UPDATE orders SET status='SHIPPED', version=8 WHERE id=? AND version=7   -- 0 rows → OptimisticLockException
```
Best when conflicts are rare. No locks are held while the user thinks. On conflict: retry (the
`@Retryable(includes = ObjectOptimisticLockingFailureException.class)` on `OrderApplicationService.ship`),
or return HTTP 409 (`GlobalExceptionHandler`).

**Pessimistic locking (`SELECT ... FOR UPDATE`)**, used by `cancel()`:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
Optional<Order> findByIdForUpdate(UUID id);
```
Best when conflicts are likely or a retry is expensive (cancel races with the saga's confirm).
**Always set a lock timeout**, or a stuck transaction blocks threads forever.

**`FOR UPDATE SKIP LOCKED`**, used by `OutboxRepository`: competing relay instances each take a
*different* batch of rows. It's a queue on Postgres with no double processing.

**Atomic conditional update (no read-modify-write)**, used by inventory (Mongo) and bulk JPQL:
```js
db.stock.findAndModify({query: {_id: sku, available: {$gte: qty}}, update: {$inc: {available: -qty, reserved: qty}}})
```
This is the strongest pattern against overselling: the check and the write are one atomic operation.

## 5. Read-only transactions

`@Transactional(readOnly = true)` on `OrderQueryService`:
* Hibernate skips dirty checking (no snapshot copies), so less CPU and memory.
* The JDBC connection gets `setReadOnly(true)`. Routing data sources can send it to a **read replica**.
* It documents intent.

## 6. Transactions + events

```java
// inside the business transaction:
registerEvent(new OrderStatusChanged(...));              // AbstractAggregateRoot → published on save()

@TransactionalEventListener(phase = AFTER_COMMIT)        // NotificationPublisher
@Async                                                   // don't block the request thread
void on(OrderStatusChanged e) { rabbit.convertAndSend(...); }
```

* `AFTER_COMMIT` guarantees you never email about an order that rolled back.
* It does **not** guarantee delivery: if RabbitMQ is down after commit, the email is lost. That's
  acceptable for notifications; for business events use the **outbox** (next section).
* Inside an `AFTER_COMMIT` listener the transaction is already committed. New database work needs
  `REQUIRES_NEW`.

## 7. The transactional outbox (dual-write problem)

```java
@Transactional
public Order place(...) {
    orders.save(order);                 // ┐ same Postgres transaction:
    events.publish(new OrderPlaced(..)) // ┘ INSERT INTO outbox — both commit or neither
}
// OutboxRelay (@Scheduled): SELECT ... FOR UPDATE SKIP LOCKED → kafka.send().get() → mark published
```

Never do `db.save(); kafka.send();` in one method. Either step can fail after the other succeeded.
The outbox turns "exactly once" into "**at least once + idempotent consumer**", which is achievable.

## 8. Programmatic transactions

`TransactionTemplate` (used in `OutboxRelay`) gives explicit boundaries in loops: one short transaction
per batch, instead of one huge transaction holding locks for the whole run.

```java
tx.executeWithoutResult(status -> { var batch = outbox.lockBatch(100); ... });
```

## 9. Multiple transaction managers

* **Mongo** (inventory-service): `MongoTransactionManager` must be declared explicitly, needs a
  replica set, and is `@Primary` + named `transactionManager` so that plain `@Transactional` uses it.
* **Kafka transactions** (`transaction-id-prefix`): the producer sends inside a Kafka transaction. With
  `read_committed` consumers this gives exactly-once *within Kafka*. Combining it with a DB transaction
  is still "best effort 1PC" (commit the DB, then commit Kafka), which is why consumers are idempotent anyway.
* JTA/XA (two-phase commit) exists but is avoided: slow, fragile, and poorly supported by modern brokers.

## 10. Testing transactions

* `@DataJpaTest` wraps each test in a rolled-back transaction, which is fast but can hide bugs that
  only appear on commit (constraint deferral, `AFTER_COMMIT` listeners). `OrderFlowIT` therefore runs
  real commits.
* Test lazy-loading problems with `spring.jpa.open-in-view=false` (set in this repo). The view layer
  must not trigger SQL.
