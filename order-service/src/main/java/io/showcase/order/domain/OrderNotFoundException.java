package io.showcase.order.domain;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {

    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order " + orderId + " not found");
        this.orderId = orderId;
    }

    public UUID orderId() {
        return orderId;
    }
}
