# Architecture Decision Records

An ADR captures one significant decision: the context, the options considered, the decision and its
consequences. ADRs are immutable once accepted; a later decision **supersedes** an earlier one instead
of editing it. They answer the question new team members always ask: "why is it like this?"

| # | Decision | Status |
|---|---|---|
| [0001](0001-transactional-outbox.md) | Publish integration events through a transactional outbox | Accepted |
| [0002](0002-kafka-for-events-rabbitmq-for-commands.md) | Kafka for domain events, RabbitMQ for notification commands | Accepted |
| [0003](0003-database-per-service-postgres-and-mongodb.md) | Database per service: PostgreSQL for orders, MongoDB for inventory | Accepted |
| [0004](0004-choreographed-saga-with-sync-precheck.md) | Choreographed saga with a synchronous gRPC pre-check | Accepted |
| [0005](0005-spring-boot-4.md) | Target Spring Boot 4 / Spring Framework 7 on Java 21 | Accepted |

Template: [`template.md`](template.md).
