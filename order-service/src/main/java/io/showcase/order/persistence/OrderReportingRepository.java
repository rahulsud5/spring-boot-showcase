package io.showcase.order.persistence;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * COMPLEX SQL for reporting, written as plain SQL with {@link JdbcClient}.
 *
 * <p><b>Why not JPA here?</b> Reporting queries return SHAPES, not entities, and use database features
 * JPQL doesn't express well: window functions, CTEs (WITH), recursive CTEs, LATERAL joins, GROUPING
 * SETS, FILTER clauses, percentiles and JSONB operators. Use the ORM for transactional aggregates
 * and SQL for analytics. They coexist happily in one transaction manager.
 *
 * <p>{@code .query(Record.class)} maps columns to record components by name ({@code moving_avg_7d}
 * binds to {@code movingAvg7d}). All parameters are BOUND ({@code :from}), never concatenated, so SQL
 * injection is impossible.
 *
 * <p>Every query is explained, together with the index it relies on, in
 * docs/05-database-indexes-and-queries.md. Try them with {@code EXPLAIN (ANALYZE, BUFFERS)} on the
 * demo data set.
 */
@Repository
public class OrderReportingRepository {

    private final JdbcClient jdbc;

    public OrderReportingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------------------------
    // 1. WINDOW FUNCTIONS: running total, 7-day moving average, day-over-day delta
    // -------------------------------------------------------------------------------------------
    public record DailyRevenue(LocalDate day, long orders, BigDecimal revenue, BigDecimal runningRevenue,
                               BigDecimal movingAvg7d, BigDecimal deltaVsPreviousDay) implements Serializable {}

    /**
     * A CTE (WITH) aggregates per day; window functions then look ACROSS rows without collapsing them:
     * <ul>
     *   <li>{@code SUM() OVER (ORDER BY day ROWS UNBOUNDED PRECEDING)}: running total</li>
     *   <li>{@code AVG() OVER (... ROWS BETWEEN 6 PRECEDING AND CURRENT ROW)}: 7-day moving average</li>
     *   <li>{@code LAG(revenue)}: the previous row's value</li>
     * </ul>
     * It uses ix_orders_status_created_covering for the date-range filter.
     */
    public List<DailyRevenue> dailyRevenue(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                WITH daily AS (
                    SELECT (o.created_at AT TIME ZONE 'UTC')::date AS day,
                           count(*)                             AS orders,
                           sum(o.total_amount)                  AS revenue
                      FROM orders o
                     WHERE o.status <> 'CANCELLED'
                       AND o.created_at >= :from
                       AND o.created_at <  :to
                     GROUP BY 1
                )
                SELECT day,
                       orders,
                       revenue,
                       sum(revenue) OVER (ORDER BY day ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_revenue,
                       round(avg(revenue) OVER (ORDER BY day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS moving_avg_7d,
                       coalesce(revenue - lag(revenue) OVER (ORDER BY day), 0)                         AS delta_vs_previous_day
                  FROM daily
                 ORDER BY day
                """)
                .param("from", from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().atOffset(java.time.ZoneOffset.UTC))
                .param("to", to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().atOffset(java.time.ZoneOffset.UTC))
                .query(DailyRevenue.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 2. TOP-N PER GROUP with DENSE_RANK: the best-selling SKUs per shipping country
    // -------------------------------------------------------------------------------------------
    public record TopSku(String country, String sku, long unitsSold, int rank) {}

    /**
     * "Top N per group" is a classic interview question. PARTITION BY restarts the ranking for each
     * country. DENSE_RANK gives ties the same rank without gaps (1, 1, 2); RANK would leave gaps
     * (1, 1, 3); ROW_NUMBER breaks ties arbitrarily (1, 2, 3). A window function's result can't be
     * filtered in its own WHERE, hence the wrapping subquery.
     */
    public List<TopSku> topSkusPerCountry(int topN) {
        return jdbc.sql("""
                SELECT country, sku, units_sold, rank
                  FROM (
                        SELECT o.shipping_country                     AS country,
                               l.sku,
                               sum(l.quantity)                        AS units_sold,
                               dense_rank() OVER (PARTITION BY o.shipping_country
                                                  ORDER BY sum(l.quantity) DESC) AS rank
                          FROM orders o
                          JOIN order_lines l ON l.order_id = o.id
                         WHERE o.status IN ('CONFIRMED', 'SHIPPED', 'DELIVERED')
                         GROUP BY o.shipping_country, l.sku
                       ) ranked
                 WHERE rank <= :topN
                 ORDER BY country, rank, sku
                """)
                .param("topN", topN)
                .query(TopSku.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 3. RECURSIVE CTE: walk the customer referral TREE
    // -------------------------------------------------------------------------------------------
    public record ReferralNode(String customerId, String referredBy, int depth, String path) {}

    /**
     * WITH RECURSIVE = an anchor query (the root customer) UNION ALL a recursive step (the customers
     * referred by the rows found so far), repeated until no new rows appear. It's the standard
     * technique for hierarchies: org charts, category trees, bills of materials. The depth guard
     * protects against cycles in bad data. ix_customers_referred_by makes each step an index lookup.
     */
    public List<ReferralNode> referralTree(String rootCustomerId, int maxDepth) {
        return jdbc.sql("""
                WITH RECURSIVE referrals AS (
                    SELECT c.id, c.referred_by, 0 AS depth, c.id::text AS path
                      FROM customers c
                     WHERE c.id = :root
                    UNION ALL
                    SELECT child.id, child.referred_by, r.depth + 1, r.path || ' > ' || child.id
                      FROM customers child
                      JOIN referrals r ON child.referred_by = r.id
                     WHERE r.depth < :maxDepth
                )
                SELECT id AS customer_id, coalesce(referred_by, '') AS referred_by, depth, path
                  FROM referrals
                 ORDER BY depth, customer_id
                """)
                .param("root", rootCustomerId)
                .param("maxDepth", maxDepth)
                .query(ReferralNode.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 4. LATERAL JOIN: the latest N orders of EACH customer
    // -------------------------------------------------------------------------------------------
    public record RecentOrder(String customerId, UUID orderId, String status, BigDecimal totalAmount, Instant createdAt) {}

    /**
     * {@code CROSS JOIN LATERAL (subquery)} runs the subquery once PER OUTER ROW and may reference it
     * (c.id). Each execution is a tiny "LIMIT n" index scan on ix_orders_customer_created. That's much
     * faster than ranking ALL orders with ROW_NUMBER when customers have long histories.
     */
    public List<RecentOrder> latestOrdersPerCustomer(List<String> customerIds, int perCustomer) {
        return jdbc.sql("""
                SELECT c.id AS customer_id, o.id AS order_id, o.status, o.total_amount, o.created_at
                  FROM customers c
                 CROSS JOIN LATERAL (
                        SELECT id, status, total_amount, created_at
                          FROM orders
                         WHERE customer_id = c.id
                         ORDER BY created_at DESC, id DESC
                         LIMIT :perCustomer
                       ) o
                 WHERE c.id IN (:ids)
                 ORDER BY c.id, o.created_at DESC
                """)
                .param("ids", customerIds)                 // expands to (?, ?, ?)
                .param("perCustomer", perCustomer)
                .query(RecentOrder.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 5. NTILE SEGMENTATION (RFM-style): split customers into quartiles by lifetime value
    // -------------------------------------------------------------------------------------------
    public record CustomerSegment(String customerId, long orders, BigDecimal lifetimeValue, int valueQuartile,
                                  long daysSinceLastOrder) {}

    /** NTILE(4) assigns quartile buckets. The view v_customer_lifetime_value hides the join + aggregation. */
    public List<CustomerSegment> customerSegments() {
        return jdbc.sql("""
                SELECT customer_id,
                       orders,
                       lifetime_value,
                       ntile(4) OVER (ORDER BY lifetime_value DESC)                      AS value_quartile,
                       coalesce(extract(day FROM now() - last_order_at)::bigint, -1)     AS days_since_last_order
                  FROM v_customer_lifetime_value
                 WHERE orders > 0
                 ORDER BY lifetime_value DESC
                 LIMIT 500
                """)
                .query(CustomerSegment.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 6. GROUPING SETS / ROLLUP: subtotals and a grand total in ONE scan
    // -------------------------------------------------------------------------------------------
    public record RevenueRollup(String currency, String status, long orders, BigDecimal revenue) {}

    /**
     * ROLLUP(currency, status) produces the groups (currency, status), (currency) and () (the grand
     * total) in one pass. GROUPING() tells a real NULL from a "subtotal" row.
     */
    public List<RevenueRollup> revenueRollup(Instant since) {
        return jdbc.sql("""
                SELECT CASE WHEN grouping(currency) = 1 THEN 'ALL' ELSE currency END AS currency,
                       CASE WHEN grouping(status)   = 1 THEN 'ALL' ELSE status   END AS status,
                       count(*)          AS orders,
                       sum(total_amount) AS revenue
                  FROM orders
                 WHERE created_at >= :since
                 GROUP BY ROLLUP (currency, status)
                 ORDER BY grouping(currency), currency, grouping(status), status
                """)
                .param("since", since.atOffset(java.time.ZoneOffset.UTC))
                .query(RevenueRollup.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 7. PERCENTILES + FILTER: the order value distribution per channel (a JSONB attribute)
    // -------------------------------------------------------------------------------------------
    public record ChannelStats(String channel, long orders, long cancelled, BigDecimal p50, BigDecimal p95) {}

    /**
     * {@code percentile_cont(0.95) WITHIN GROUP (ORDER BY x)}: an ordered-set aggregate (median and p95).
     * {@code count(*) FILTER (WHERE ...)}: a conditional count, cleaner than SUM(CASE WHEN ...).
     * {@code metadata ->> 'channel'}: extracts a JSONB field as text.
     */
    public List<ChannelStats> statsPerChannel() {
        return jdbc.sql("""
                SELECT coalesce(metadata ->> 'channel', 'unknown')                                     AS channel,
                       count(*)                                                                         AS orders,
                       count(*) FILTER (WHERE status = 'CANCELLED')                                     AS cancelled,
                       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY total_amount)::numeric, 2)    AS p50,
                       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY total_amount)::numeric, 2)    AS p95
                  FROM orders
                 GROUP BY 1
                 ORDER BY orders DESC
                """)
                .query(ChannelStats.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 8. ANTI-JOIN (NOT EXISTS): customers with no order in the last N days (win-back campaign)
    // -------------------------------------------------------------------------------------------
    public record DormantCustomer(String customerId, String email) {}

    /**
     * NOT EXISTS is the correct and fast anti-join. Avoid {@code NOT IN (subquery)}: if the subquery
     * returns a single NULL, NOT IN yields no rows at all (three-valued logic).
     * The lower(email) expression matches the expression index ux_customers_email_active.
     */
    public List<DormantCustomer> dormantCustomers(int days) {
        return jdbc.sql("""
                SELECT c.id AS customer_id, lower(c.email) AS email
                  FROM customers c
                 WHERE c.deleted_at IS NULL
                   AND NOT EXISTS (
                         SELECT 1
                           FROM orders o
                          WHERE o.customer_id = c.id
                            AND o.created_at >= now() - make_interval(days => :days)
                       )
                 ORDER BY c.id
                 LIMIT 1000
                """)
                .param("days", days)
                .query(DormantCustomer.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 9. TRIGRAM fuzzy search
    // -------------------------------------------------------------------------------------------
    public record CustomerMatch(String customerId, String fullName, double similarity) {}

    /** {@code %} is pg_trgm's similarity operator, served by the GIN trigram index on full_name. */
    public List<CustomerMatch> searchCustomers(String term) {
        return jdbc.sql("""
                SELECT id AS customer_id, full_name, similarity(full_name, :term) AS similarity
                  FROM customers
                 WHERE full_name % :term OR full_name ILIKE '%' || :term || '%'
                 ORDER BY similarity DESC
                 LIMIT 20
                """)
                .param("term", term)
                .query(CustomerMatch.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 10. Batch loading for GraphQL (the fix for N+1): one IN (...) query for many orders
    // -------------------------------------------------------------------------------------------
    public record OrderLineRow(UUID orderId, short lineNo, String sku, int quantity, BigDecimal unitAmount, BigDecimal lineTotal) {}

    public List<OrderLineRow> linesForOrders(List<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT order_id, line_no, sku, quantity, unit_amount, quantity * unit_amount AS line_total
                  FROM order_lines
                 WHERE order_id IN (:ids)
                 ORDER BY order_id, line_no
                """)
                .param("ids", orderIds)
                .query(OrderLineRow.class)
                .list();
    }

    // -------------------------------------------------------------------------------------------
    // 11. Trigger-maintained history + UPSERT + materialized view refresh
    // -------------------------------------------------------------------------------------------
    public record StatusHistoryEntry(String fromStatus, String toStatus, Instant changedAt) implements Serializable {}

    public List<StatusHistoryEntry> statusHistory(UUID orderId) {
        return jdbc.sql("""
                SELECT coalesce(from_status, '') AS from_status, to_status, changed_at
                  FROM order_status_history
                 WHERE order_id = :orderId
                 ORDER BY changed_at, id
                """)
                .param("orderId", orderId)
                .query(StatusHistoryEntry.class)
                .list();
    }

    /**
     * CONCURRENTLY: readers keep using the old contents while the new ones are computed. It needs
     * the UNIQUE index created in V3. Scheduled by ReportingJobs.
     */
    public void refreshDailySales() {
        jdbc.sql("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_daily_sales").update();
    }

    /**
     * UPSERT ({@code INSERT ... ON CONFLICT DO UPDATE}): an atomic "insert or update", with no
     * read-then-write race. EXCLUDED refers to the row we tried to insert.
     */
    public int upsertCustomerTier(String customerId, String email, String fullName, String tier) {
        return jdbc.sql("""
                INSERT INTO customers (id, email, full_name, tier)
                VALUES (:id, :email, :fullName, :tier)
                ON CONFLICT (id) DO UPDATE
                   SET tier = EXCLUDED.tier
                 WHERE customers.tier IS DISTINCT FROM EXCLUDED.tier
                """)
                .param("id", customerId)
                .param("email", email)
                .param("fullName", fullName)
                .param("tier", tier)
                .update();
    }
}
