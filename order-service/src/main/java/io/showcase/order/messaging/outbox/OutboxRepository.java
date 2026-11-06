package io.showcase.order.messaging.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * {@code FOR UPDATE SKIP LOCKED}: the key to running the relay on MANY instances safely.
     * Each instance locks a batch of rows; rows already locked by another instance are SKIPPED
     * instead of waited for. That gives parallel work distribution with no double publishing and no
     * coordinator. (The same trick turns Postgres into a decent job queue.)
     * The partial index ix_outbox_unpublished serves the WHERE + ORDER BY.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE published_at IS NULL
             ORDER BY created_at
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(int batchSize);

    long countByPublishedAtIsNull();

    @Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
    Optional<Instant> oldestUnpublishedCreatedAt();

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt < :cutoff")
    int deletePublishedBefore(Instant cutoff);
}
