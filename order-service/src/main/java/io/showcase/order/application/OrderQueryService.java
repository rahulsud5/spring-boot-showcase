package io.showcase.order.application;

import io.showcase.order.application.OrderViews.CursorPage;
import io.showcase.order.application.OrderViews.OrderDetails;
import io.showcase.order.application.OrderViews.OrderSummary;
import io.showcase.order.domain.Order;
import io.showcase.order.domain.OrderNotFoundException;
import io.showcase.order.persistence.OrderReportingRepository;
import io.showcase.order.persistence.OrderReportingRepository.DailyRevenue;
import io.showcase.order.persistence.OrderReportingRepository.StatusHistoryEntry;
import io.showcase.order.persistence.OrderRepository;
import io.showcase.order.persistence.OrderSearchCriteria;
import io.showcase.order.persistence.OrderSpecifications;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * READ side (a light form of CQRS: separate classes and models for reads and writes, same database).
 *
 * <p>{@code @Transactional(readOnly = true)}:
 * <ul>
 *   <li>Hibernate skips dirty checking and snapshot copies, so less memory and CPU per loaded entity.</li>
 *   <li>The JDBC connection is flagged read-only; a routing DataSource can send it to a READ REPLICA
 *       (see docs/04-transactions.md: LazyConnectionDataSourceProxy + AbstractRoutingDataSource).</li>
 *   <li>It still gives a consistent persistence context for lazy loading inside the method.</li>
 * </ul>
 *
 * <p>METHOD SECURITY ({@code @EnableMethodSecurity} in SecurityConfig):
 * <ul>
 *   <li>{@code @PreAuthorize}: checked BEFORE the method runs, against roles and arguments.</li>
 *   <li>{@code @PostAuthorize}: checked AFTER, against the RETURN value. That's ideal for "users may
 *       only see their OWN orders", which needs the loaded data to decide. It prevents IDOR (Insecure
 *       Direct Object Reference), the #1 OWASP category, Broken Access Control.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orders;
    private final OrderReportingRepository reporting;

    public OrderQueryService(OrderRepository orders, OrderReportingRepository reporting) {
        this.orders = orders;
        this.reporting = reporting;
    }

    @PostAuthorize("hasRole('ADMIN') or returnObject.customerId() == authentication.name")
    public OrderDetails get(UUID orderId) {
        return orders.findWithLinesById(orderId)        // @EntityGraph: order + lines in ONE query
                .map(OrderDetails::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * KEYSET pagination with Spring Data's {@link Window} / {@link ScrollPosition} API.
     *
     * <p>OFFSET pagination ({@code LIMIT 20 OFFSET 100000}) makes the database read and discard
     * 100,000 rows, and pages shift when new rows are inserted. Keyset pagination remembers the LAST
     * row seen and asks for rows "after" it:
     * {@code WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT 20}. That's
     * constant time on any page, thanks to the composite index ix_orders_customer_created.
     */
    public CursorPage<OrderSummary> ordersOf(String customerId, @Nullable String cursor, int size) {
        ScrollPosition position = cursor == null ? ScrollPosition.keyset() : decodeCursor(cursor);
        Window<Order> window = orders.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId, position, Limit.of(size));

        List<OrderSummary> items = window.getContent().stream().map(OrderSummary::from).toList();
        String next = window.hasNext() && !items.isEmpty() ? encodeCursor(items.getLast()) : null;
        return new CursorPage<>(items, next);
    }

    /** Dynamic filtering with the Specification API: the WHERE clause is assembled from the provided criteria only. */
    public Page<OrderSummary> search(OrderSearchCriteria criteria, Pageable pageable) {
        return orders.findAll(OrderSpecifications.matching(criteria), pageable).map(OrderSummary::from);
    }

    /**
     * {@code @Cacheable}: the first call runs the (heavy) window-function query and stores the result
     * in Redis under "dailySales::&lt;from&gt;-&lt;to&gt;". Later calls are served from the cache until the
     * TTL configured in CacheConfig expires. The key is built with SpEL from the arguments.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Cacheable(cacheNames = "dailySales", key = "#from + '-' + #to")
    public List<DailyRevenue> dailyRevenue(LocalDate from, LocalDate to) {
        return reporting.dailyRevenue(from, to);
    }

    @PostAuthorize("hasRole('ADMIN') or returnObject.customerId() == authentication.name")
    public OrderSummary summary(UUID orderId) {
        return orders.findById(orderId).map(OrderSummary::from).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<OrderSummary> recentByStatus(io.showcase.order.domain.OrderStatus status, int limit) {
        var page = org.springframework.data.domain.PageRequest.of(0, limit,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        return orders.findAll(OrderSpecifications.hasStatus(status), page).map(OrderSummary::from).getContent();
    }

    /** Lines of many orders in ONE query, grouped by order id (used by the GraphQL @BatchMapping). */
    public Map<UUID, List<OrderViews.LineView>> linesFor(List<UUID> orderIds) {
        return reporting.linesForOrders(orderIds).stream().collect(java.util.stream.Collectors.groupingBy(
                OrderReportingRepository.OrderLineRow::orderId,
                java.util.stream.Collectors.mapping(
                        r -> new OrderViews.LineView(r.lineNo(), r.sku(), r.quantity(), r.unitAmount(), r.lineTotal()),
                        java.util.stream.Collectors.toList())));
    }

    public List<StatusHistoryEntry> history(UUID orderId) {
        return reporting.statusHistory(orderId);
    }

    // The cursor is opaque to clients (base64), so its format can change without breaking them
    private static String encodeCursor(OrderSummary last) {
        String raw = last.createdAt() + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static ScrollPosition decodeCursor(String cursor) {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed cursor");
        }
        return ScrollPosition.forward(Map.of("createdAt", Instant.parse(parts[0]), "id", UUID.fromString(parts[1])));
    }
}
