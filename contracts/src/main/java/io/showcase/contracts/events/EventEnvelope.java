package io.showcase.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The wire format of every Kafka message: metadata + payload.
 *
 * <pre>{@code
 * {
 *   "eventId":       "0192f5c4-...",     // unique: consumers deduplicate on it (idempotent consumer)
 *   "eventType":     "OrderPlaced",      // tells consumers how to read "payload"
 *   "schemaVersion": 1,                  // payload evolution without breaking old consumers
 *   "aggregateId":   "0192f5c3-...",     // also the Kafka key, so ordering per aggregate holds
 *   "occurredAt":    "2026-11-06T05:00:00Z",
 *   "payload":       { ...the event record... }
 * }
 * }</pre>
 *
 * <p>Using an explicit {@code eventType} instead of Jackson polymorphic type info
 * ({@code @JsonTypeInfo} with class names) keeps the contract language-neutral and avoids
 * deserialization gadget attacks, where a crafted class name in the payload instantiates
 * arbitrary types (OWASP: insecure deserialization).
 *
 * <p>This is close to the CloudEvents specification ({@code id}, {@code type}, {@code source},
 * {@code time}, {@code data}), which is worth adopting when many teams share a broker.
 */
public record EventEnvelope<T extends IntegrationEvent>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateId,
        Instant occurredAt,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
    }

    /** Factory that derives the type name and aggregate id from the payload, so they can't drift apart. */
    public static <T extends IntegrationEvent> EventEnvelope<T> wrap(T payload, Instant now) {
        return new EventEnvelope<>(UuidV7.next(), EventTypes.nameOf(payload), 1,
                payload.orderId().toString(), now, payload);
    }
}
