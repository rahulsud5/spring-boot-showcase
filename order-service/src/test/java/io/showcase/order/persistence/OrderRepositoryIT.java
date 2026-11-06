package io.showcase.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.showcase.contracts.events.UuidV7;
import io.showcase.order.domain.Address;
import io.showcase.order.domain.DiscountPolicy;
import io.showcase.order.domain.LineDraft;
import io.showcase.order.domain.Money;
import io.showcase.order.domain.Order;
import io.showcase.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JPA SLICE + REAL POSTGRES: {@code @DataJpaTest} loads only JPA, Flyway, repositories and
 * JdbcClient. Each test runs in a transaction that is ROLLED BACK at the end, so tests stay isolated
 * without cleanup code.
 *
 * <p>{@code replace = NONE}: by default {@code @DataJpaTest} swaps in an embedded database. We want the
 * Testcontainers Postgres instead, so Flyway runs the REAL migrations (indexes, trigger, views) and the
 * native queries are verified against real PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({OrderReportingRepository.class, PersistenceConfig.class})
class OrderRepositoryIT {

    /** Static container + @Container: one container for all tests in this class. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    OrderRepository orders;
    @Autowired
    OrderReportingRepository reporting;

    private Order save(String customer, String amount) {
        Order order = Order.place(UuidV7.next(), customer, new Address("1 Main St", "Springfield", "1000", "NL"),
                List.of(new LineDraft("SKU-0001", 1, Money.of(amount, "EUR"))), new DiscountPolicy.None(), "{\"channel\":\"mobile\"}");
        return orders.saveAndFlush(order);
    }

    @Test
    void keysetPaginationWalksAllPagesWithoutDuplicates() {
        for (int i = 0; i < 5; i++) save("alice", "10.00");

        Window<Order> first = orders.findByCustomerIdOrderByCreatedAtDescIdDesc("alice", ScrollPosition.keyset(), Limit.of(2));
        Window<Order> second = orders.findByCustomerIdOrderByCreatedAtDescIdDesc("alice", first.positionAt(first.size() - 1), Limit.of(2));

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.getContent()).doesNotContainAnyElementsOf(first.getContent());
    }

    @Test
    void specificationsComposeDynamicFilters() {
        save("alice", "10.00");
        save("alice", "99.00");
        save("bob", "99.00");

        var criteria = new OrderSearchCriteria("alice", OrderStatus.PENDING, null, null, new BigDecimal("50"), "SKU-0001");
        assertThat(orders.findAll(OrderSpecifications.matching(criteria))).hasSize(1);
    }

    @Test
    void jsonbContainmentQueryUsesNativeOperator() {
        save("alice", "10.00");
        assertThat(orders.findByMetadataContaining("{\"channel\": \"mobile\"}", 10)).isNotEmpty();
        assertThat(orders.findByMetadataContaining("{\"channel\": \"web\"}", 10)).isEmpty();
    }

    @Test
    void triggerRecordsStatusHistory() {
        Order order = save("alice", "10.00");
        order.confirm();
        orders.saveAndFlush(order);

        assertThat(reporting.statusHistory(order.getId()))
                .extracting(OrderReportingRepository.StatusHistoryEntry::toStatus)
                .containsExactly("PENDING", "CONFIRMED");
    }

    @Test
    void windowFunctionReportAndRecursiveCteRun() {
        save("alice", "10.00");
        save("alice", "30.00");

        var days = reporting.dailyRevenue(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(days).isNotEmpty();
        assertThat(days.getLast().runningRevenue()).isEqualByComparingTo("40.00");

        // V3 seeds alice → bob → carol as a referral chain
        assertThat(reporting.referralTree("alice", 5))
                .extracting(OrderReportingRepository.ReferralNode::customerId)
                .containsExactly("alice", "bob", "carol");
    }

    @Test
    void dtoProjectionAggregatesPerStatus() {
        save("alice", "10.00");
        save("bob", "5.00");
        assertThat(orders.countByStatusSince(Instant.EPOCH))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.status()).isEqualTo(OrderStatus.PENDING);
                    assertThat(c.orders()).isEqualTo(2);
                });
    }
}
