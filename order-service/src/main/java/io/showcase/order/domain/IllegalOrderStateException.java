package io.showcase.order.domain;

import java.util.UUID;

/**
 * A domain rule was violated. It's an UNCHECKED exception, so Spring's default rollback rule
 * applies: {@code @Transactional} rolls back on RuntimeException and Error, but NOT on checked
 * exceptions unless configured with {@code rollbackFor}. GlobalExceptionHandler maps it to HTTP 409.
 */
public class IllegalOrderStateException extends RuntimeException {

    public IllegalOrderStateException(UUID orderId, OrderStatus from, OrderStatus to) {
        super("Order %s cannot move from %s to %s".formatted(orderId, from, to));
    }
}
