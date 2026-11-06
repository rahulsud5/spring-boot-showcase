package io.showcase.order.application;

import io.showcase.order.domain.Order;
import io.showcase.order.domain.OrderLine;
import io.showcase.order.domain.OrderStatus;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * READ MODELS returned by the application layer. Entities never leave the transaction boundary:
 * <ul>
 *   <li>no LazyInitializationException when a controller touches {@code order.getLines()} after the
 *       transaction closed (and we keep {@code spring.jpa.open-in-view=false})</li>
 *   <li>no accidental exposure of internal fields (version, audit columns) in the API</li>
 *   <li>immutable records are safe to cache (they implement {@code Serializable} for the Redis cache)</li>
 * </ul>
 */
public final class OrderViews {

    private OrderViews() {
    }

    public record OrderSummary(UUID id, String customerId, OrderStatus status, BigDecimal total, String currency,
                               @Nullable Instant createdAt) implements Serializable {

        public static OrderSummary from(Order o) {
            return new OrderSummary(o.getId(), o.getCustomerId(), o.getStatus(), o.getTotal().amount(),
                    o.getTotal().currency(), o.getCreatedAt());
        }
    }

    public record LineView(short lineNo, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal)
            implements Serializable {

        public static LineView from(OrderLine l) {
            return new LineView(l.getLineNo(), l.getSku(), l.getQuantity(), l.getUnitPrice().amount(), l.lineTotal().amount());
        }
    }

    public record OrderDetails(UUID id, String customerId, OrderStatus status, BigDecimal total, BigDecimal discount,
                               String currency, String shippingCity, String shippingCountry,
                               @Nullable String cancellationReason, long version, @Nullable Instant createdAt,
                               List<LineView> lines) implements Serializable {

        /** Must be called INSIDE the transaction, while the lazy lines can still be loaded. */
        public static OrderDetails from(Order o) {
            return new OrderDetails(o.getId(), o.getCustomerId(), o.getStatus(), o.getTotal().amount(),
                    o.getDiscountAmount(), o.getTotal().currency(), o.getShippingAddress().city(),
                    o.getShippingAddress().country(), o.getCancellationReason(), o.getVersion(), o.getCreatedAt(),
                    o.getLines().stream().map(LineView::from).toList());
        }
    }

    /** Keyset ("cursor") page: an opaque cursor instead of a page number. Stable under concurrent inserts. */
    public record CursorPage<T>(List<T> items, @Nullable String nextCursor) {}
}
