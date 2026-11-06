# 06 · SQL vs NoSQL: PostgreSQL (orders) vs MongoDB (inventory)

This repo uses both on purpose, so the same concepts can be compared side by side.

## 1. Why each service chose its store

**order-service → PostgreSQL**
* Orders have a stable, relational shape (customer → orders → lines → status history).
* Reporting needs joins, window functions, rollups and percentiles ([docs/05](05-database-indexes-and-queries.md)).
* Money needs constraints (`CHECK (total_amount >= 0)`), foreign keys and multi-row ACID transactions
  (order + outbox row).
* JSONB gives schema flexibility where needed (`orders.metadata`) without giving up SQL.

**inventory-service → MongoDB**
* Products have category-specific attributes (a laptop has `ramGb`, a shirt has `size`), a natural
  fit for documents.
* The hot path is single-document: "decrement stock for SKU X if available". MongoDB updates one
  document atomically, no transaction needed.
* Reads fetch a whole product in one document (no joins).

## 2. Concept mapping

| Concept | PostgreSQL (order-service) | MongoDB (inventory-service) |
|---|---|---|
| Schema | Flyway migrations, `ddl-auto=validate` | Schemaless; validation in code (optionally `$jsonSchema`); evolve with versioned documents |
| Unit of atomicity | Transaction across any rows | **Single document** (multi-document transactions need a replica set and cost more) |
| Relationships | Foreign keys, joins | Embedding (`Product.attributes`), references, `$lookup` (rare) |
| Money | `numeric(12,2)` | `Decimal128` (`@Field(targetType = DECIMAL128)` on `Price`) — **never doubles** |
| Composite index | `(customer_id, created_at DESC)` | `@CompoundIndex(def = "{'category': 1, 'price.amount': 1}")` (ESR rule: Equality, Sort, Range) |
| Full-text | `tsvector` / pg_trgm | `@TextIndexed` + `$text` |
| Expiring data | Cleanup job + BRIN | **TTL index** on `Reservation.createdAt` (`expireAfter = "90d"`; MongoDB deletes old reservations) |
| Array indexing | GIN on JSONB / arrays | Multikey index (`tags`) |
| Analytics | SQL: CTE, window functions | Aggregation pipeline (`CatalogAnalytics`: `$match` → `$group` → `$lookup` → `$sort`) |
| Concurrency control | `FOR UPDATE`, `@Version`, conditional `UPDATE` | `findAndModify` with a condition, `@Version` |
| Pagination | Keyset with composite index | Range on indexed field + `limit` |
| Scaling writes | Vertical + partitioning; sharding via Citus | Native sharding by shard key |
| Spring support | Spring Data JPA, `JdbcClient` | Spring Data MongoDB, `MongoTemplate` |

## 3. Same operation, two stores

**"Reserve stock if available" (atomic check-and-act)**

```sql
-- Postgres: one statement, row lock held only for the update
UPDATE stock SET available = available - :qty, reserved = reserved + :qty
 WHERE sku = :sku AND available >= :qty;           -- 0 rows updated → insufficient stock
```

```java
// MongoDB (StockService): the query and the update are one atomic operation on one document
mongo.findAndModify(
    query(where("_id").is(sku).and("available").gte(qty)),
    new Update().inc("available", -qty).inc("reserved", qty),
    FindAndModifyOptions.options().returnNew(true),
    StockItem.class);                               // null → insufficient stock
```

An order with several SKUs touches several documents, so `StockService.reserveAll` runs inside a
**multi-document transaction** (`@Transactional` with `MongoTransactionManager`): either all lines
are reserved or none.

**"Revenue per category"**

```sql
SELECT p.category, sum(l.quantity * l.unit_amount) FROM order_lines l JOIN products p USING (sku) GROUP BY 1;
```

```java
// CatalogAnalytics (simplified): $match first so it can use indexes, then $lookup the stock documents
newAggregation(
    match(where("price.amount").gt(BigDecimal.ZERO)),
    lookup("stock", "_id", "_id", "stock"), unwind("stock", true),   // preserveNull = LEFT JOIN
    group("category").count().as("products").avg("price.amount").as("avgPrice"),
    sort(Sort.Direction.DESC, "products"));
```

## 4. Decision guide

Choose **relational** when: the data is highly connected, the access patterns are unknown or ad-hoc
(analytics), you need multi-entity invariants, or the team knows SQL well. *This is the right default.*

Choose **document** when: aggregates are naturally self-contained, the schema varies per record, the
hot path is read/write by key, or you need horizontal write scale with sharding.

Other NoSQL families, for completeness:

| Type | Example | Used here for |
|---|---|---|
| Key-value | Redis | Cache, idempotency keys, rate limiting ([docs/11](11-caching-redis-async.md)) |
| Wide-column | Cassandra, DynamoDB | Massive write throughput with known access patterns |
| Search | OpenSearch | Full-text and faceting (CQRS read model) |
| Graph | Neo4j | Deep relationship traversal (the recursive CTE in Postgres covers simple cases) |
| Log | Kafka | An event store / stream ([docs/07](07-messaging-kafka-vs-rabbitmq.md)) |

## 5. Pitfalls

* **Unbounded arrays** in documents (e.g. every reservation embedded in a product). Documents have a 16 MB
  limit, and every update rewrites the array. Reservations are a separate collection here.
* `$lookup`-heavy designs mean you wanted a relational database.
* MongoDB transactions need a **replica set**, even in development (docker-compose runs `--replSet rs0`
  with a single node; Testcontainers `MongoDBContainer` does this automatically).
* JSONB is great, but don't hide core, queried fields in it: they lose types, constraints and statistics.
