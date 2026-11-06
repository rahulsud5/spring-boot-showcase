-- =============================================================================
-- V2: TRANSACTIONAL OUTBOX + IDEMPOTENT CONSUMER tables
-- =============================================================================
-- THE DUAL-WRITE PROBLEM: "save the order in Postgres AND publish OrderPlaced to Kafka" can't be one
-- atomic operation; there's no distributed transaction between them. Every naive ordering breaks:
--   - save, then publish: a crash between the two leaves a saved order but no event (inventory never reserves)
--   - publish, then save: the save fails, but the event for a non-existent order is already out
--
-- THE OUTBOX PATTERN: write the event into this table IN THE SAME DATABASE TRANSACTION as the order.
-- Either both commit or neither does. A relay (OutboxRelay.java) then publishes the unpublished rows to
-- Kafka and marks them as published. The result is AT-LEAST-ONCE delivery, which is why consumers
-- must be idempotent (processed_messages below).
--
-- Production alternative to polling: change data capture (Debezium reads the Postgres WAL and streams
-- outbox inserts to Kafka with no polling and lower latency).
-- =============================================================================

CREATE TABLE outbox_events (
    id             uuid         PRIMARY KEY,            -- = eventId in the envelope (consumers dedupe on it)
    aggregate_type varchar(64)  NOT NULL,
    aggregate_id   varchar(64)  NOT NULL,              -- = Kafka message key (ordering per order)
    event_type     varchar(128) NOT NULL,
    topic          varchar(128) NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    published_at   timestamptz,
    attempts       integer      NOT NULL DEFAULT 0,
    last_error     text
);

-- The relay's hot query:
--     SELECT ... FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
-- PARTIAL index: it holds only UNPUBLISHED rows, so it stays tiny even when the table has millions of
-- published rows awaiting cleanup.
CREATE INDEX ix_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;

-- Retention: the cleanup job deletes published rows older than N days (BRIN keeps that range scan cheap)
CREATE INDEX brin_outbox_published_at
    ON outbox_events USING brin (published_at);

-- -----------------------------------------------------------------------------
-- IDEMPOTENT CONSUMER: Kafka and RabbitMQ deliver AT LEAST once (redeliveries after rebalances,
-- timeouts or crashes). Recording each processed message id IN THE SAME TRANSACTION as the business
-- change turns "at least once" into "effectively once":
--     INSERT INTO processed_messages ... ON CONFLICT DO NOTHING
--     -> 1 row inserted : first time, so process it
--     -> 0 rows         : duplicate, so skip it
-- -----------------------------------------------------------------------------
CREATE TABLE processed_messages (
    consumer     varchar(64) NOT NULL,
    message_id   uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, message_id)                 -- the composite PK IS the dedupe constraint
);
