package io.showcase.inventory.stock;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A stock reservation for one order. {@code _id = orderId} makes reserving IDEMPOTENT for free: a
 * redelivered OrderPlaced event can't create a second reservation, because the primary key forbids it.
 *
 * <p>TTL INDEX: {@code expireAfter = "90d"} on createdAt tells MongoDB to DELETE documents
 * automatically 90 days after that timestamp (a background task runs about every 60 s). It's ideal
 * for sessions, tokens and audit trails with a retention policy; no cleanup job required.
 */
@Document("reservations")
public record Reservation(
        @Id String orderId,
        Status status,
        List<Line> lines,
        String reason,
        @Indexed(name = "ttl_created_at", expireAfter = "90d") Instant createdAt) {

    public enum Status { RESERVED, REJECTED, RELEASED }

    public record Line(String sku, int quantity) {}

    public Reservation withStatus(Status newStatus) {
        return new Reservation(orderId, newStatus, lines, reason, createdAt);
    }
}
