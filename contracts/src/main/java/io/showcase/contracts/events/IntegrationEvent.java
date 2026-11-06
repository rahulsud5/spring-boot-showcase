package io.showcase.contracts.events;

import java.util.UUID;

/**
 * Root of every event that crosses a service boundary.
 *
 * <p><b>Domain events vs integration events.</b> A <i>domain event</i> is internal to one service
 * (for example order-service's {@code OrderStatusChanged}) and may change freely. An
 * <i>integration event</i> is a public contract consumed by other teams, so it's versioned
 * (topic suffix {@code .v1}), backward compatible, and lives in this shared module.
 *
 * <p>The hierarchy is {@code sealed} (Java 17+). Consumers can then {@code switch} over the event
 * types with no {@code default} branch, and the compiler flags any new event type a consumer
 * forgets to handle. That's compile-time safety for a message-driven system.
 */
public sealed interface IntegrationEvent permits OrderEvent, InventoryEvent {

    /** The aggregate this event belongs to. Used as the Kafka message key, so per-order ordering is kept. */
    UUID orderId();
}
