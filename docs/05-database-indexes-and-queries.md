# 05 · Database: indexes and complex queries (PostgreSQL 17)

The schema lives in Flyway migrations (`order-service/src/main/resources/db/migration`). Every index
there has a comment explaining **which query it serves**. An index without a query is pure write cost.

## 1. Index cookbook

| # | Index | Type | Serves | Migration |
|---|---|---|---|---|
| 1 | `ix_orders_customer_created (customer_id, created_at DESC, id DESC)` | Composite B-tree | "My orders", newest first, keyset pagination | V1 |
| 2 | `ix_orders_status_created_covering (status, created_at) INCLUDE (total_amount, currency)` | Covering | Ops dashboard: **index-only scan** | V1 |
| 3 | `ix_orders_active_created ... WHERE status IN ('PENDING','CONFIRMED')` | Partial | Active-order queue; small because shipped and cancelled orders are excluded | V1 |
| 4 | `ix_orders_metadata USING gin (metadata jsonb_path_ops)` | GIN on JSONB | `metadata @> '{"channel":"mobile"}'` containment | V1 |
| 5 | `ix_orders_campaign ((metadata->>'campaign')) WHERE metadata ? 'campaign'` | Expression + partial | `metadata->>'campaign' = 'black-friday'` | V1 |
| 6 | `ux_customers_email_active (lower(email)) WHERE deleted_at IS NULL` | Unique + expression + partial | Case-insensitive unique email among *active* customers | V1 |
| 7 | `ix_customers_full_name_trgm USING gin (full_name gin_trgm_ops)` | GIN trigram (pg_trgm) | `ILIKE '%smi%'`, `similarity()` fuzzy search | V1 |
| 8 | `brin_order_status_history_changed_at`, `brin_audit_log_occurred_at` | BRIN | Time-range scans on append-only tables (tiny index) | V1 |
| 9 | `ix_outbox_unpublished (created_at) WHERE published_at IS NULL` | Partial | Outbox relay hot query; stays small forever | V2 |
| 10 | `ux_mv_daily_sales (sales_day, currency)` on a materialized view | Unique | Required for `REFRESH MATERIALIZED VIEW CONCURRENTLY` | V3 |
| 11 | `ix_order_lines_sku_covering (sku) INCLUDE (quantity, unit_amount)` built **CONCURRENTLY** | Covering, online build | SKU analytics without blocking writes | V4 |

### Rules of thumb

* **Column order in composite indexes:** equality columns first, then range/sort columns.
  `(customer_id, created_at)` serves `WHERE customer_id = ? ORDER BY created_at`; the reverse order doesn't.
* **Left-prefix rule:** `(a, b, c)` serves `a`, `a,b`, `a,b,c`, but not `b` alone.
* **Don't index low-selectivity columns alone** (a `status` with 5 values). Combine them, or use a
  partial index for the rare values.
* The **primary key of `order_lines` is `(order_id, line_no)`**, so it already serves `WHERE order_id = ?`.
  A separate `order_id` index would be redundant (V1 comments explain this).
* **Every index slows down writes** and uses RAM/disk. Check for unused ones:
  `SELECT indexrelname, idx_scan FROM pg_stat_user_indexes WHERE idx_scan = 0;`
* **Production DDL:** `CREATE INDEX CONCURRENTLY` (doesn't block writes, can't run inside a
  transaction). See V4 and its `.sql.conf` (`executeInTransaction=false`).

## 2. Real EXPLAIN plans (measured on 50,000 demo orders)

The `demo` profile loads `db/demo/R__demo_data.sql` (50k orders, 150k lines). These plans were captured
during verification:

| Query | Plan | Notes |
|---|---|---|
| Keyset page for one customer | `Index Scan using ix_orders_customer_created`, **0.05 ms** | No sort node: the index order is the result order |
| Dashboard `SELECT status, created_at, total_amount, currency ... WHERE status=? AND created_at>=?` | `Index Only Scan using ix_orders_status_created_covering`, **Heap Fetches: 0** | INCLUDE columns answered from the index; the visibility map was current after VACUUM |
| Active orders | `Index Scan using ix_orders_active_created` | The query's `WHERE` must imply the index predicate |
| `metadata @> '{"channel":"mobile"}'` | `Bitmap Index Scan on ix_orders_metadata` → Bitmap Heap Scan | Bitmap scans are typical for GIN with many matches |
| `metadata->>'campaign' = 'black-friday'` | `Index Scan using ix_orders_campaign` | The expression must match the index definition exactly |
| Trigram search on `customers`, outbox relay | `Seq Scan` | **Expected**: tables were tiny/empty, and the planner rightly prefers a seq scan over a few pages |

Try it yourself:

```sql
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT id, status, total_amount, created_at
  FROM orders
 WHERE customer_id = 'cust-0042'
   AND (created_at, id) < ('2026-06-01T00:00:00Z', 'ffffffff-ffff-ffff-ffff-ffffffffffff')
 ORDER BY created_at DESC, id DESC
 LIMIT 20;
```

**Reading a plan:** check the node type (Seq / Index / Index Only / Bitmap), *rows estimated vs actual*
(a big mismatch means stale statistics → `ANALYZE`), `Buffers: shared hit/read` (I/O), and Sort nodes
with `external merge` (work_mem too small).

## 3. Complex query catalog (`OrderReportingRepository`, via `JdbcClient`)

All 13 queries were executed against PostgreSQL 17 with demo data during verification.

| # | Method | Technique |
|---|---|---|
| 1 | `dailyRevenue` | CTE + window functions: running total `SUM() OVER (ROWS UNBOUNDED PRECEDING)`, 7-day moving `AVG`, `LAG` delta |
| 2 | `topSkusPerCountry` | **Top-N per group** with `DENSE_RANK() OVER (PARTITION BY country ORDER BY units DESC)` |
| 3 | `referralTree` | **Recursive CTE** walking the customer referral graph with a depth limit and cycle protection (path array) |
| 4 | `latestOrdersPerCustomer` | **`LATERAL` join**: "for each customer, their latest N orders", using index #1 per customer |
| 5 | `customerSegments` | `NTILE(4)` quartiles + `CASE` segmentation |
| 6 | `revenueRollup` | `GROUP BY ROLLUP (currency, status)` with subtotals and a grand total, `GROUPING()` |
| 7 | `statsPerChannel` | `percentile_cont(0.5/0.95) WITHIN GROUP`, `count(*) FILTER (WHERE ...)` on a JSONB key |
| 8 | `dormantCustomers` | Anti-join `NOT EXISTS` (correct with NULLs, unlike `NOT IN`) |
| 9 | `searchCustomers` | pg_trgm `%` operator + `similarity()` ranking |
| 10 | `linesForOrders` | `= ANY(:ids)` batch fetch (one query instead of N) for GraphQL `@BatchMapping` |
| 11 | `statusHistory` | Rows maintained by a **trigger** (`trg_orders_status_history`) |
| 12 | `refreshDailySales` | `REFRESH MATERIALIZED VIEW CONCURRENTLY mv_daily_sales` (readers aren't blocked) |
| 13 | `upsertCustomerTier` | `INSERT ... ON CONFLICT (...) DO UPDATE ... WHERE` (upsert with a conditional update) |

Plus JPA-side queries in `OrderRepository`:

* `@EntityGraph(attributePaths = "lines")`: fetch join to avoid **N+1**.
* `Window<Order> findFirst20By...(ScrollPosition)`: Spring Data **keyset scrolling**.
* Class-based projection `select new io.showcase.order.persistence.StatusCount(o.status, count(o))`.
* Interface projection (`OrderSummary`): select only the needed columns.
* `@Modifying` bulk JPQL update (`clearAutomatically = true` because bulk updates bypass the persistence context).
* Native JSONB query (`metadata @> cast(:json as jsonb)`).
* `OrderSpecifications`: dynamic filters combined with `Specification.allOf(...)`.

## 4. Pagination: offset vs keyset

| | `OFFSET 100000 LIMIT 20` | Keyset `WHERE (created_at, id) < (?, ?) LIMIT 20` |
|---|---|---|
| Cost | Reads and discards 100,000 rows | Seeks directly through the index |
| Stability | Rows shift when new data arrives (duplicates or gaps) | Stable |
| Jump to page N | Yes | No (next/previous only) |
| Use for | Small admin tables | Feeds, APIs, infinite scroll (`GET /api/orders/mine?cursor=`) |

The tie-breaker `id` is essential: `created_at` alone isn't unique.

## 5. JPA performance checklist

* `spring.jpa.open-in-view=false`: no lazy loading from controllers.
* Watch for N+1 queries: entity graphs, `JOIN FETCH`, `@BatchSize`, or DTO projections.
* Sequences with `allocationSize = 50` (`OrderLine`) and `hibernate.jdbc.batch_size` for batched inserts.
  `IDENTITY` columns disable insert batching.
* `@Version` for optimistic locking; `ddl-auto=validate` (Flyway owns the schema).
* Log slow queries in the DB (`log_min_duration_statement`), not with `show-sql` in production.
* Size the Hikari pool from database capacity, not app threads (virtual threads make this crucial).
