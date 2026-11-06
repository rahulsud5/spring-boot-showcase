# 0002 · Kafka for domain events, RabbitMQ for notification commands

* **Status:** Accepted
* **Date:** 2026-11-01

## Context
Two kinds of asynchronous traffic exist:
1. **Domain events** between order and inventory (`OrderPlaced`, `StockReserved`...). Several consumers
   may care, now or later (analytics, search, audit); per-order ordering matters; replay is valuable.
2. **Commands** to notification-service ("send this email"). One consumer; routing by template;
   per-message retries and dead-lettering; a lost-and-replayed history has no value.

## Options considered
1. **Kafka for everything:** one platform, but per-message retry/DLQ and routing need extra code
   (retry topics), and "work queue" semantics fit less naturally.
2. **RabbitMQ for everything:** rich routing and acks, but no long retention or replay for events, and
   fan-out to future consumers requires changing topology.
3. **AWS SNS/SQS for everything:** managed, but ties local development and the showcase to AWS and
   offers weaker ordering (FIFO queues have throughput limits).
4. **Both, each for its strength.**

## Decision
Use **Kafka** for domain events (`orders.events.v1`, `inventory.events.v1`; key = orderId; `acks=all`,
idempotent producer; Kafka transactions in inventory-service) and **RabbitMQ** for commands
(topic exchange `notifications`, quorum queue, DLX + DLQ, stateless retry with back-off).
SQS is used only for the AWS-native return-request integration.

## Consequences
* ✅ Each workload gets the semantics it needs; the repo demonstrates both side by side
  ([docs/07](../07-messaging-kafka-vs-rabbitmq.md)).
* ⚠️ Two brokers to operate, monitor and secure. In a real company, choose based on the platform
  team's capabilities; managed offerings (MSK, Amazon MQ) reduce the cost.
* ⚠️ Developers must understand two programming models (`@KafkaListener` vs `@RabbitListener`).
