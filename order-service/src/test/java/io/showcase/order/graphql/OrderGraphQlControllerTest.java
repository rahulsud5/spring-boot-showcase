package io.showcase.order.graphql;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.showcase.order.application.OrderApplicationService;
import io.showcase.order.application.OrderQueryService;
import io.showcase.order.application.OrderViews.LineView;
import io.showcase.order.application.OrderViews.OrderSummary;
import io.showcase.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * GRAPHQL SLICE TEST: {@code @GraphQlTest} loads the schema plus the GraphQL controllers only, and
 * {@link GraphQlTester} executes queries against them without HTTP. It checks schema-to-controller
 * wiring, field resolvers and batch loading.
 */
@GraphQlTest(OrderGraphQlController.class)
class OrderGraphQlControllerTest {

    @Autowired
    GraphQlTester graphQlTester;

    @MockitoBean
    OrderQueryService queries;
    @MockitoBean
    OrderApplicationService orders;

    @Test
    void resolvesAnOrderWithMoneyAndBatchLoadedLines() {
        UUID id = UUID.randomUUID();
        given(queries.summary(id)).willReturn(new OrderSummary(id, "alice", OrderStatus.CONFIRMED,
                new BigDecimal("42.50"), "EUR", Instant.parse("2026-11-06T10:00:00Z")));
        given(queries.linesFor(anyList())).willReturn(Map.of(id, List.of(
                new LineView((short) 1, "SKU-0001", 2, new BigDecimal("21.25"), new BigDecimal("42.50")))));

        graphQlTester.document("""
                        query($id: ID!) {
                          order(id: $id) { id status total { amount currency } lines { sku quantity lineTotal } }
                        }
                        """)
                .variable("id", id)
                .execute()
                .path("order.status").entity(String.class).isEqualTo("CONFIRMED")
                .path("order.total.amount").entity(String.class).isEqualTo("42.50")
                .path("order.lines[0].sku").entity(String.class).isEqualTo("SKU-0001");

        verify(queries, times(1)).linesFor(anyList());    // the BATCH loader ran once
    }
}
