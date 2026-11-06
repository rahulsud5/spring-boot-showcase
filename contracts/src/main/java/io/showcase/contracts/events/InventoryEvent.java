package io.showcase.contracts.events;

import java.util.List;
import java.util.UUID;

/**
 * Events published by inventory-service on topic {@code inventory.events.v1}: the replies in the
 * order saga.
 *
 * <pre>
 *  order-service                      Kafka                    inventory-service
 *  ─────────────                      ─────                    ─────────────────
 *  place order (PENDING) ──OrderPlaced──► orders.events.v1 ──► reserve stock
 *                                                              │
 *  confirm order ◄──StockReserved── inventory.events.v1 ◄──────┤ success
 *  cancel order  ◄──StockRejected── inventory.events.v1 ◄──────┘ failure (compensation)
 * </pre>
 *
 * This is a <b>choreography</b> saga: no central coordinator, each service reacts to events. The
 * alternative, an <b>orchestration</b> saga, is compared in docs/00-architecture-and-system-design.md.
 */
public sealed interface InventoryEvent extends IntegrationEvent {

    record StockReserved(UUID orderId, String reservationId) implements InventoryEvent {}

    record StockRejected(UUID orderId, String reason, List<String> unavailableSkus) implements InventoryEvent {
        public StockRejected {
            unavailableSkus = unavailableSkus == null ? List.of() : List.copyOf(unavailableSkus);
        }
    }

    /** Stock released after an OrderCancelled (the compensating action). */
    record StockReleased(UUID orderId) implements InventoryEvent {}
}
