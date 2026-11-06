package io.showcase.order.domain;

/**
 * The order lifecycle as an explicit STATE MACHINE.
 *
 * <pre>
 *   PENDING ──► CONFIRMED ──► SHIPPED ──► DELIVERED
 *      │            │
 *      └────────────┴──► CANCELLED
 * </pre>
 *
 * <p>The transition table is an exhaustive {@code switch} EXPRESSION (Java 14+). Add a new enum
 * constant and the compiler forces you to decide its transitions. Encoding the rules in the domain
 * (not in controllers or SQL) means every entry point (REST, GraphQL, Kafka) gets the same rules.
 */
public enum OrderStatus {
    PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED;

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == SHIPPED || next == CANCELLED;
            case SHIPPED -> next == DELIVERED;
            case DELIVERED, CANCELLED -> false;          // terminal states
        };
    }

    public boolean isActive() {
        return this == PENDING || this == CONFIRMED;
    }
}
