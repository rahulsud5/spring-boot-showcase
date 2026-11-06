-- =============================================================================
-- V3: reference data + reporting views
-- =============================================================================

-- Customers matching the users of auth-server (the JWT "sub" claim = customers.id).
-- bob was referred by alice, carol by bob: a referral chain for the recursive CTE demo.
INSERT INTO customers (id, email, full_name, tier, referred_by) VALUES
    ('alice', 'alice@example.com', 'Alice Anderson', 'GOLD',     NULL),
    ('bob',   'bob@example.com',   'Bob Brown',      'SILVER',   'alice'),
    ('carol', 'carol@example.com', 'Carol Clark',    'STANDARD', 'bob'),
    ('machine-client', 'ops@example.com', 'Partner Integration', 'STANDARD', NULL);

-- -----------------------------------------------------------------------------
-- MATERIALIZED VIEW: pre-computed daily sales. A regular VIEW re-runs its query every time; a
-- MATERIALIZED view stores the result like a table and is refreshed on a schedule. That trades
-- freshness for very cheap reads on heavy aggregations.
-- -----------------------------------------------------------------------------
CREATE MATERIALIZED VIEW mv_daily_sales AS
SELECT (o.created_at AT TIME ZONE 'UTC')::date               AS sales_day,
       o.currency,
       count(*)                                              AS order_count,
       count(*) FILTER (WHERE o.status = 'CANCELLED')        AS cancelled_count,   -- FILTER clause: a conditional aggregate
       sum(o.total_amount) FILTER (WHERE o.status <> 'CANCELLED') AS revenue,
       avg(o.total_amount)                                   AS avg_order_value
FROM orders o
GROUP BY 1, 2
WITH DATA;

-- A UNIQUE index is REQUIRED for  REFRESH MATERIALIZED VIEW CONCURRENTLY, which rebuilds the view
-- without locking out readers (OrderReportingRepository.refreshDailySales()).
CREATE UNIQUE INDEX ux_mv_daily_sales ON mv_daily_sales (sales_day, currency);

-- -----------------------------------------------------------------------------
-- REGULAR VIEW: customer lifetime value. It encapsulates a join + aggregation behind a stable name.
-- -----------------------------------------------------------------------------
CREATE VIEW v_customer_lifetime_value AS
SELECT c.id                                   AS customer_id,
       c.tier,
       count(o.id)                            AS orders,
       coalesce(sum(o.total_amount), 0)       AS lifetime_value,
       max(o.created_at)                      AS last_order_at
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id AND o.status <> 'CANCELLED'
WHERE c.deleted_at IS NULL
GROUP BY c.id, c.tier;
