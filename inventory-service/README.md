# inventory-service (HTTP 8082, gRPC 9090)

Owns the product catalog and stock, in **MongoDB**.

| Area | Where to look | Guide |
|---|---|---|
| Documents, `Decimal128` money, compound/text/multikey/TTL indexes | `catalog/Product`, `stock/Reservation` | [06](../docs/06-sql-vs-nosql.md) |
| Aggregation pipeline (`$match` → `$lookup` → `$group` → `$sort`) | `catalog/CatalogAnalytics` | [06](../docs/06-sql-vs-nosql.md) |
| Atomic conditional updates (`findAndModify`) + multi-document transactions | `stock/StockService` | [04](../docs/04-transactions.md), [06](../docs/06-sql-vs-nosql.md) |
| gRPC server: unary + server streaming | `grpc/InventoryGrpcService` | [08](../docs/08-rest-grpc-graphql.md) |
| Kafka consumer with exactly-once (Kafka transactions) | `messaging/OrderEventsListener` | [07](../docs/07-messaging-kafka-vs-rabbitmq.md) |
| `MongoTransactionManager` as the primary transaction manager; topic beans; seed data | `config/InventoryConfig` | |

REST: `GET /api/products/{sku}`, `/{sku}/price`, `/search?q=`, `/category/{category}`,
`/analytics/categories`.

## Run

MongoDB must run as a **replica set** for transactions (`docker compose up -d` does that).

```bash
mvn -pl inventory-service -am spring-boot:run
grpcurl -plaintext localhost:9090 list        # the reflection service is registered by Boot 4.1 gRPC support
```

The seed runner (`@Profile("!test")`) inserts demo products and stock at startup if the collections are empty.
