# contracts

A plain library (not a Spring Boot app) shared by the services: the **published contracts** between them.

| Contract | Files | Notes |
|---|---|---|
| Integration events | `events/IntegrationEvent`, `OrderEvent`, `InventoryEvent` | Sealed interfaces + records; the compiler knows every event type |
| Envelope | `events/EventEnvelope` | `eventId` (UUIDv7, used for dedupe), `eventType`, `schemaVersion`, `aggregateId` (Kafka key), `occurredAt`, `payload` |
| Type registry | `events/EventTypes` | Built from `getPermittedSubclasses()`: name ↔ class, no arbitrary polymorphic deserialization |
| Messaging names | `messaging/Messaging` | Topic, exchange, queue and routing-key constants |
| gRPC | `src/main/proto/inventory/v1/inventory.proto` | Code is generated at build time by `protobuf-maven-plugin` |

## Evolving contracts safely

* **Add, don't change:** new optional fields are compatible; renaming or retyping a field isn't.
* Bump `schemaVersion` for semantic changes and keep readers tolerant of unknown fields.
* Protobuf: never reuse field numbers; mark removed ones `reserved`; the package carries the major version (`inventory.v1`).
* Breaking changes → a new topic (`orders.events.v2`) or a new proto package, run side by side during migration.
* Sharing a contracts jar couples release cycles; at larger scale use a schema registry (Avro/Protobuf)
  and generate classes per consumer.
