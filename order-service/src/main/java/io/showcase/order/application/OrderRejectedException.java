package io.showcase.order.application;

/** The fraud check rejected the order. Mapped to HTTP 422 by GlobalExceptionHandler. */
public class OrderRejectedException extends RuntimeException {

    public OrderRejectedException(String message) {
        super(message);
    }
}
