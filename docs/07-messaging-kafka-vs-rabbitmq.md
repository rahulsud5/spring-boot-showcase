# 07 · Messaging: Kafka vs RabbitMQ

This system uses **both**, each for what it's best at:

* **Kafka** carries **domain events** between order- and inventory-service (`orders.events.v1`,
  `inventory.events.v1`): a durable, replayable, ordered log.
* **RabbitMQ** carries **commands** to notification-service (exchange `notifications`, queue
  `notifications.email`): a smart broker with routing, per-message acknowledgement, retries and dead-lettering.

See [ADR 0002](adr/0002-kafka-for-events-rabbitmq-for-commands.md) for the decision record.

## 1. Mental models

```
KAFKA: a distributed, partitioned, append-only LOG
  orders.events.v1   P0: [e1][e4][e7] ...      ← consumers track their own OFFSET
                     P1: [e2][e5] ...          ← messages stay (retention), so they can be REPLAYED
                     P2: [e3][e6][e8] ...      ← order is guaranteed only WITHIN a partition
  group "inventory-service": consumer A ← P0,P1   consumer B ← P2   (1 partition → 1 consumer per group)
  group "analytics":         reads the same events independently, from its own offsets

RABBITMQ: a MESSAGE BROKER with exchanges, bindings and queues
  producer → [exchange "notifications" (topic)] --routing key "notification.email.order-confirmed"-->
             binding "notification.email.#" → [queue notifications.email (quorum)] → consumer (ack/nack)
                                                         │ rejected / retries exhausted
                                                         ▼
                                       [exchange notifications.dlx] → [notifications.email.dlq]
  Once ACKed, the message is GONE. Competing consumers share one queue.
```

## 2. Comparison

| Aspect | Kafka (event streaming) | RabbitMQ (message broker) |
|---|---|---|
| Model | Log; consumers pull and track offsets | Queues; broker pushes and tracks acks |
| Retention | Time/size-based (days, or forever with compaction) | Until consumed (streams aside) |
| Replay | ✓ Reset offsets, new consumer groups read history | ✗ (except RabbitMQ Streams) |
| Ordering | Per partition (use a key) | Per queue, single consumer |
| Routing | Topic + key only; consumers filter | Rich: direct, topic, fanout, headers exchanges |
| Fan-out | Many consumer groups read the same topic for free | One queue per subscriber, bound to an exchange |
| Throughput | Very high (sequential disk I/O, batching, zero-copy) | High, lower than Kafka at scale |
| Per-message features | Minimal | TTL, priority, delayed retry, DLX per message |
| Delivery semantics | At-least-once; **exactly-once** with idempotent producer + transactions + `read_committed` | At-least-once with acks + publisher confirms |
| Scaling consumers | Up to the number of partitions | Add consumers to a queue freely |
| Back-pressure | Consumer lag (consumers pull at their own pace) | Prefetch (`spring.rabbitmq.listener.simple.prefetch`) |
| Spring | `@KafkaListener`, `KafkaTemplate`, `DefaultErrorHandler`, `@RetryableTopic` | `@RabbitListener`, `RabbitTemplate`, `Declarables`, retry interceptor |

**Rule of thumb:** "something happened, and anyone may care, maybe later" → **Kafka**.
"Please do this one thing, once" (with routing/priorities/retries) → **RabbitMQ** (or SQS).

## 3. Kafka in this repo

**Producer (order-service `OutboxRelay`, inventory-service):**
```yaml
spring.kafka.producer:
  acks: all                       # wait for all in-sync replicas
  properties.enable.idempotence: true   # broker dedupes producer retries
  compression-type: lz4
  properties.linger.ms: 5         # small batching window = much higher throughput
```
* **Key = orderId**, so all events of one order land on the same partition, **in order**.
* inventory-service uses **Kafka transactions** (`transaction-id-prefix: inventory-tx-`): consume →
  process → produce happen atomically within Kafka. Consumers use `isolation-level: read_committed`.

**Consumer (order-service `InventoryEventsListener` → `InventoryEventHandler`):**
* Manual deserialization of `EventEnvelope` → resolve the type via `EventTypes` → exhaustive switch.
* **Idempotent:** `processed_messages (consumer, message_id)` insert in the same DB transaction as the
  state change. A duplicate becomes a no-op.
* **Errors:** `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries(3)` (3 retries after the first attempt), then a
  `DeadLetterPublishingRecoverer` → `<topic>-dlt`. Non-retryable exceptions
  (`UnknownEventTypeException`, deserialization errors) go **straight to the DLT**: retrying them can
  never succeed.
* `concurrency: 3` listener threads; partitions = 6 leave room for scaling to 2 instances × 3.

**Topic design**
* Name with a version: `orders.events.v1`. Breaking schema changes → a new topic, or a `schemaVersion`
  field in the envelope (used here) with tolerant readers.
* Partitions are the unit of parallelism. **Increasing them later re-maps keys** (`hash(key) % N`),
  which breaks per-key ordering for in-flight data. Choose generously up front.
* Production: `replication.factor=3`, `min.insync.replicas=2`, `acks=all`.

> **Lesson learned (happened during verification):** the topic was first created with 3 partitions by
> an init script, then a `NewTopic` bean with 6 partitions *increased* it at startup. The already-running
> consumer had been assigned only partitions 0-2, and events for some orders landed on partition 5,
> so the saga "hung" until the consumer restarted and rebalanced. Kafka clients refresh metadata
> periodically (`metadata.max.age.ms`, 5 min by default), so it would have healed eventually.
> Fix: one owner for topic configuration, created before consumers start (here the `kafka-init`
> container uses 6 partitions, matching the beans). See [docs/20](20-lessons-learned.md).

## 4. RabbitMQ in this repo

**Topology (notification-service `RabbitTopologyConfig`, declared with `Declarables`):**
```
exchange notifications (topic, durable)
  └─ binding notification.email.# → queue notifications.email
                                       x-queue-type: quorum                (replicated, Raft-based)
                                       x-dead-letter-exchange: notifications.dlx
exchange notifications.dlx → queue notifications.email.dlq
```
* **Quorum queues** are the modern default for durability (classic mirrored queues are removed in RabbitMQ 4).
* **Retry:** `spring.rabbitmq.listener.simple.retry` (stateless, exponential back-off, in-memory);
  after the last attempt the message is **rejected without requeue** → the DLX routes it to the DLQ.
  `DeadLetterAlerts` publishes an SNS alert ([docs/15](15-aws.md)).
* **Producer (order-service `NotificationPublisher`):** after the DB commit (`@TransactionalEventListener(AFTER_COMMIT)`),
  `@Async`. Publisher confirms (`publisher-confirm-type: correlated`) and `mandatory` returns detect
  unroutable messages.
* **Idempotency:** a bounded LRU of recently processed message ids in `NotificationListener`. Emails
  are a "best effort, avoid obvious duplicates" case; use Redis or a DB table for stronger guarantees.

## 5. Delivery semantics cheat sheet

| Semantics | How | Risk |
|---|---|---|
| At-most-once | Commit offset / ack **before** processing | Message loss on crash |
| At-least-once | Commit / ack **after** processing (Spring's default) | Duplicates → make consumers idempotent |
| Exactly-once (Kafka → Kafka) | Idempotent producer + transactions + `read_committed` | Doesn't cover side effects outside Kafka (DB, email) |
| Effectively-once (end to end) | At-least-once + idempotent consumer (dedupe table, natural keys, upserts) | **What this repo implements** |

## 6. Alternatives

* **AWS SQS/SNS:** managed queue and pub/sub (SQS listener in notification-service, see [docs/15](15-aws.md)).
* **Amazon MSK / Confluent Cloud:** managed Kafka. **Amazon MQ:** managed RabbitMQ.
* **Spring Cloud Stream:** a binder abstraction over Kafka/RabbitMQ. Portable, but hides
  broker-specific features this repo wants to show, so the native Spring Kafka and Spring AMQP APIs are used.
* **RabbitMQ Streams** (log-like, replayable) and **Kafka share groups (KIP-932, queue semantics)**
  blur the boundary; the rule of thumb above still holds.
