package io.showcase.order.messaging.kafka;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplication store for the idempotent consumer pattern.
 *
 * <p>{@code INSERT ... ON CONFLICT DO NOTHING} is ATOMIC: even if two instances process the same
 * redelivered message at the same instant, the primary key lets exactly one insert succeed. A
 * "SELECT first, then INSERT" check would race.
 */
@Component
public class ProcessedMessageStore {

    private final JdbcClient jdbc;

    public ProcessedMessageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true on the first delivery, false for a duplicate */
    @Transactional(propagation = Propagation.MANDATORY)     // must share the business transaction
    public boolean markProcessed(String consumer, UUID messageId) {
        int inserted = jdbc.sql("""
                INSERT INTO processed_messages (consumer, message_id)
                VALUES (:consumer, :messageId)
                ON CONFLICT DO NOTHING
                """)
                .param("consumer", consumer)
                .param("messageId", messageId)
                .update();
        return inserted == 1;
    }
}
