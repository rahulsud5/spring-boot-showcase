package io.showcase.order.persistence;

import io.showcase.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Optional search filters: every null field simply doesn't contribute a predicate. */
public record OrderSearchCriteria(
        @Nullable String customerId,
        @Nullable OrderStatus status,
        @Nullable Instant from,
        @Nullable Instant to,
        @Nullable BigDecimal minTotal,
        @Nullable String sku) {
}
