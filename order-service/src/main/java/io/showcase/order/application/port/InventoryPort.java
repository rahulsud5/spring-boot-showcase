package io.showcase.order.application.port;

import java.util.List;

/**
 * OUTBOUND PORT (Hexagonal / Ports and Adapters architecture).
 *
 * <p>The application layer declares WHAT it needs ("check availability") in its own terms. The
 * infrastructure provides HOW: here a gRPC adapter (GrpcInventoryAdapter). Benefits:
 * <ul>
 *   <li>Use cases are unit-testable with a trivial fake; no gRPC server or mocks of generated stubs.</li>
 *   <li>The transport can change (gRPC to REST to in-process) without touching business code.</li>
 *   <li>Dependencies point INWARD: infrastructure depends on the application, never the other way
 *       (the Dependency Inversion Principle, the "D" in SOLID).</li>
 * </ul>
 */
public interface InventoryPort {

    Availability checkAvailability(List<RequestedItem> items);

    record RequestedItem(String sku, int quantity) {}

    /**
     * The three outcomes as a sealed type. {@code Unknown} models "inventory is down / circuit open":
     * a DEGRADED mode where we accept the order as PENDING and let the asynchronous saga decide,
     * instead of failing the customer's request.
     */
    sealed interface Availability {
        record Available() implements Availability {}
        record Unavailable(List<String> skus) implements Availability {}
        record Unknown(String reason) implements Availability {}
    }
}
