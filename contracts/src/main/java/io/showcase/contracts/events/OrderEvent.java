package io.showcase.contracts.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Events published by order-service on topic {@code orders.events.v1}.
 *
 * <p>The records are nested inside the sealed interface, so the {@code permits} clause can be left
 * out: the compiler infers it from the nested types. The records are immutable and get
 * {@code equals/hashCode/toString} for free, which makes them ideal event payloads. Compact
 * constructors validate invariants at the boundary, so a malformed event can never be built.
 */
public sealed interface OrderEvent extends IntegrationEvent {

    /** An order was accepted and is waiting for a stock reservation (saga step 1). */
    record OrderPlaced(UUID orderId, String customerId, List<Line> lines, BigDecimal total, String currency)
            implements OrderEvent {

        public OrderPlaced {
            Objects.requireNonNull(orderId, "orderId");
            if (lines == null || lines.isEmpty()) {
                throw new IllegalArgumentException("an order needs at least one line");
            }
            lines = List.copyOf(lines);   // defensive copy: the event stays immutable even if the caller's list changes
        }

        public record Line(String sku, int quantity) {
            public Line {
                if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            }
        }
    }

    /** Stock was reserved and the order is confirmed (end of the saga's happy path). */
    record OrderConfirmed(UUID orderId) implements OrderEvent {}

    /** The order was cancelled by the customer, or by the saga (compensation). Inventory releases the stock. */
    record OrderCancelled(UUID orderId, String reason) implements OrderEvent {}
}
