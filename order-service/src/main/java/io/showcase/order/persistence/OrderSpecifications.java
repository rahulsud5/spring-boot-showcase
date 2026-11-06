package io.showcase.order.persistence;

import io.showcase.order.domain.Order;
import io.showcase.order.domain.OrderLine;
import io.showcase.order.domain.OrderStatus;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * SPECIFICATION pattern (from DDD, implemented by Spring Data on top of the JPA Criteria API): small,
 * named, COMPOSABLE predicates. A search screen with 6 optional filters would otherwise need 2^6
 * query methods or string-concatenated JPQL (an injection risk).
 *
 * <p>For full type safety, generate the JPA static metamodel ({@code Order_.status} instead of the
 * string "status") with hibernate-processor. Strings keep this example short.
 */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> hasStatus(OrderStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Order> belongsTo(String customerId) {
        return (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
    }

    public static Specification<Order> createdBetween(Instant from, Instant to) {
        return (root, query, cb) -> cb.between(root.get("createdAt"), from, to);
    }

    /** Navigates into the embedded Money value object: total.amount maps to the total_amount column. */
    public static Specification<Order> totalAtLeast(BigDecimal min) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("total").get("amount"), min);
    }

    /**
     * "Orders containing SKU X" as a correlated EXISTS subquery, rather than a JOIN. A join would
     * DUPLICATE an order that has the SKU on two lines (and break pagination counts); EXISTS returns
     * each order at most once.
     */
    public static Specification<Order> containsSku(String sku) {
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<OrderLine> line = sub.from(OrderLine.class);
            sub.select(cb.literal(1L))
               .where(cb.equal(line.get("order"), root), cb.equal(line.get("sku"), sku));
            return cb.exists(sub);
        };
    }

    /** Composes only the filters that were provided. allOf() combines them with AND. */
    public static Specification<Order> matching(OrderSearchCriteria c) {
        List<Specification<Order>> specs = new ArrayList<>();
        if (c.customerId() != null) specs.add(belongsTo(c.customerId()));
        if (c.status() != null) specs.add(hasStatus(c.status()));
        if (c.from() != null && c.to() != null) specs.add(createdBetween(c.from(), c.to()));
        if (c.minTotal() != null) specs.add(totalAtLeast(c.minTotal()));
        if (c.sku() != null) specs.add(containsSku(c.sku()));
        return Specification.allOf(specs);
    }
}
