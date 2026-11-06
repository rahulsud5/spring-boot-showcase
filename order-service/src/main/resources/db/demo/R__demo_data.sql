-- =============================================================================
-- DEMO DATA: about 50,000 orders, so EXPLAIN ANALYZE shows realistic plans. Loaded only with the "demo"
-- profile (application-demo.yml adds classpath:db/demo to spring.flyway.locations).
--
-- R__ = a REPEATABLE migration: Flyway re-runs it whenever its checksum changes, always AFTER the
-- versioned migrations. That suits views, functions and seed data. It must therefore be idempotent
-- (the guard below).
-- =============================================================================
DO
$$
BEGIN
    IF (SELECT count(*) FROM orders) > 0 THEN
        RAISE NOTICE 'demo data already present, skipping';
        RETURN;
    END IF;

    -- generate_series = a set-returning function: one INSERT ... SELECT produces thousands of rows
    INSERT INTO customers (id, email, full_name, tier, referred_by)
    SELECT 'cust-' || g,
           'customer' || g || '@example.com',
           'Customer ' || g,
           (ARRAY ['STANDARD', 'SILVER', 'GOLD'])[1 + (g % 3)],
           CASE WHEN g > 10 THEN 'cust-' || (g / 10) END            -- referral tree: cust-123 referred by cust-12
    FROM generate_series(1, 2000) AS g;

    INSERT INTO orders (id, customer_id, status, total_amount, currency,
                        shipping_street, shipping_city, shipping_postal_code, shipping_country,
                        cancellation_reason, metadata, created_at, updated_at, created_by)
    SELECT gen_random_uuid(),
           'cust-' || (1 + (g % 2000)),
           s.status,
           round((10 + random() * 490)::numeric, 2),
           (ARRAY ['EUR', 'USD'])[1 + (g % 2)],
           g || ' Demo Street', 'Springfield', '1000' || (g % 10), 'NL',
           CASE WHEN s.status = 'CANCELLED' THEN 'demo cancellation' END,
           jsonb_build_object('channel', (ARRAY ['web', 'mobile', 'partner'])[1 + (g % 3)],
                              'campaign', CASE WHEN g % 7 = 0 THEN 'black-friday' END),
           now() - make_interval(mins => g * 5),
           now() - make_interval(mins => g * 5),
           'seed'
    FROM generate_series(1, 50000) AS g
    CROSS JOIN LATERAL (
        SELECT (ARRAY ['PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'DELIVERED', 'DELIVERED', 'CANCELLED'])
                   [1 + (g % 7)] AS status
    ) s;

    INSERT INTO order_lines (id, order_id, line_no, sku, quantity, unit_amount, currency)
    SELECT nextval('order_lines_seq'), o.id, l.n,
           'SKU-' || lpad(((abs(hashtext(o.id::text)) + l.n) % 200)::text, 4, '0'),
           1 + (l.n % 3),
           round((o.total_amount / 2)::numeric, 2),
           o.currency
    FROM orders o
    CROSS JOIN generate_series(1, 2) AS l(n);

    REFRESH MATERIALIZED VIEW mv_daily_sales;
    ANALYZE orders;            -- refresh the planner's statistics after a bulk load
    ANALYZE order_lines;
END
$$;
