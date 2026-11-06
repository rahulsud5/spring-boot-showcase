# 0001 · Publish integration events through a transactional outbox

* **Status:** Accepted
* **Date:** 2026-11-01

## Context
order-service must store an order in PostgreSQL **and** publish `OrderPlaced` to Kafka. There is no
shared transaction between the two. Every naive ordering loses data on a crash:
save-then-publish loses the event; publish-then-save announces an order that doesn't exist.

## Options considered
1. **Dual write in one method** (`save(); kafkaTemplate.send();`): simple, but incorrect under failure.
2. **Distributed transaction (XA/2PC)**: Kafka doesn't participate in XA; slow and fragile anyway.
3. **Kafka transactions synchronized with the DB transaction** ("best effort 1PC"): still a window
   where the DB commits and Kafka doesn't.
4. **Transactional outbox + polling relay**: the event row commits atomically with the order; a relay
   publishes it later.
5. **Transactional outbox + CDC (Debezium)**: same table, the WAL is streamed to Kafka. Lower latency,
   but an extra platform component (Kafka Connect).

## Decision
Use a **transactional outbox** (`outbox_events`) written with `Propagation.MANDATORY`
(`OutboxWriter`), and a **polling relay** (`OutboxRelay`) that locks batches with
`FOR UPDATE SKIP LOCKED`, publishes with the aggregate id as key, waits for the broker ack, then marks
rows as published. Move to Debezium when latency or load requires it; the table design is compatible.

## Consequences
* ✅ No lost or phantom events. Business code calls a port (`IntegrationEventPublisher`) and stays
  unaware of Kafka.
* ✅ Multiple relay instances can run safely (`SKIP LOCKED`).
* ⚠️ Delivery is **at least once** (a crash after send, before marking, republishes), so every consumer
  must be idempotent (`processed_messages` table, reservation `_id = orderId`).
* ⚠️ Added latency (poll interval 500 ms) and DB load; needs cleanup (`app.outbox.retention`, BRIN index)
  and monitoring (`outbox.backlog` gauge, DEGRADED health status).
* ⚠️ Ordering is per aggregate only, guaranteed by key = orderId and single-partition delivery.
