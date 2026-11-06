package io.showcase.order.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DOMAIN event (internal to order-service), registered by the aggregate and published by Spring Data
 * on {@code save()}. Contrast it with the INTEGRATION events in the contracts module, which are the
 * public, versioned contract.
 *
 * @param from {@code null} when the order was just created
 */
public record OrderStatusChanged(UUID orderId, String customerId, @Nullable OrderStatus from, OrderStatus to, Money total) {
}
