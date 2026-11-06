package io.showcase.order.application;

import io.showcase.order.domain.Address;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Input of the "place order" use case. It's independent of HTTP or GraphQL: controllers translate
 * their request DTOs into this command, so the use case doesn't care which transport called it.
 */
public record PlaceOrderCommand(
        String customerId,
        Address shippingAddress,
        List<Item> items,
        @Nullable String discountCode,
        Map<String, String> metadata) {

    public PlaceOrderCommand {
        items = List.copyOf(items);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record Item(String sku, int quantity) {}
}
