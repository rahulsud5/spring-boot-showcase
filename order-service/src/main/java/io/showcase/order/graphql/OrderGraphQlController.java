package io.showcase.order.graphql;

import io.showcase.order.application.OrderApplicationService;
import io.showcase.order.application.OrderQueryService;
import io.showcase.order.application.OrderViews.LineView;
import io.showcase.order.application.OrderViews.OrderSummary;
import io.showcase.order.domain.OrderStatus;
import io.showcase.order.persistence.OrderReportingRepository.StatusHistoryEntry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/**
 * GraphQL API: ANNOTATED CONTROLLERS, the GraphQL counterpart of {@code @RestController}.
 *
 * <ul>
 *   <li>{@code @QueryMapping} / {@code @MutationMapping}: root fields of Query / Mutation.</li>
 *   <li>{@code @SchemaMapping}: resolves ONE field of ONE object (called per parent object).</li>
 *   <li>{@code @BatchMapping}: resolves a field for ALL parent objects AT ONCE (backed by a DataLoader).</li>
 * </ul>
 *
 * <h2>The N+1 problem in GraphQL</h2>
 * {@code { myOrders(limit: 50) { lines { sku } } }}: a naive per-order resolver for {@code lines} runs
 * 1 query for the orders + 50 queries for their lines. {@link #lines(List)} receives all 50 orders and
 * loads all lines with ONE {@code WHERE order_id IN (...)} query. Compare it with {@link #history},
 * which deliberately stays per-object to show the trade-off.
 *
 * <p>Security: the same method-security rules as REST apply, because these methods delegate to
 * OrderQueryService (@PostAuthorize) and the transport layer adds no logic of its own.
 */
@Controller
class OrderGraphQlController {

    private final OrderQueryService queries;
    private final OrderApplicationService orders;

    OrderGraphQlController(OrderQueryService queries, OrderApplicationService orders) {
        this.queries = queries;
        this.orders = orders;
    }

    @QueryMapping
    OrderSummary order(@Argument UUID id) {
        return queries.summary(id);
    }

    @QueryMapping
    List<OrderSummary> myOrders(@Argument int limit, @AuthenticationPrincipal Jwt jwt) {
        return queries.ordersOf(jwt.getSubject(), null, Math.min(limit, 100)).items();
    }

    @QueryMapping
    List<OrderSummary> ordersByStatus(@Argument OrderStatus status, @Argument int limit) {
        return queries.recentByStatus(status, Math.min(limit, 100));
    }

    @MutationMapping
    OrderSummary cancelOrder(@Argument UUID id, @Argument String reason, @AuthenticationPrincipal Jwt jwt) {
        queries.summary(id);                                  // ownership check (@PostAuthorize)
        orders.cancel(id, reason, jwt.getSubject());
        return queries.summary(id);
    }

    /** Field resolver: the Java record has (total, currency) and the schema wants Money { amount currency }. */
    @SchemaMapping(typeName = "Order", field = "total")
    MoneyGql total(OrderSummary order) {
        return new MoneyGql(order.total().toPlainString(), order.currency());
    }

    /** BATCH resolver: ONE query for the lines of every order in the response. */
    @BatchMapping(typeName = "Order", field = "lines")
    Map<OrderSummary, List<LineView>> lines(List<OrderSummary> orders) {
        Map<UUID, List<LineView>> byOrderId = queries.linesFor(orders.stream().map(OrderSummary::id).toList());
        return orders.stream().collect(java.util.stream.Collectors.toMap(
                o -> o,
                o -> byOrderId.getOrDefault(o.id(), List.of())));
    }

    /** Per-object resolver: N orders cost N history queries. Acceptable for single-order views only. */
    @SchemaMapping(typeName = "Order", field = "history")
    List<StatusHistoryEntry> history(OrderSummary order) {
        return queries.history(order.id());
    }

    record MoneyGql(String amount, String currency) {}
}
