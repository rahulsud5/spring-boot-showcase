package io.showcase.order.application;

import java.util.List;

/** Mapped to HTTP 422 Unprocessable Content by GlobalExceptionHandler. */
public class InsufficientStockException extends RuntimeException {

    private final List<String> skus;

    public InsufficientStockException(List<String> skus) {
        super("Insufficient stock for " + skus);
        this.skus = List.copyOf(skus);
    }

    public List<String> skus() {
        return skus;
    }
}
