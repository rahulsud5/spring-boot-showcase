package io.showcase.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * DOMAIN UNIT TESTS: plain JUnit 5 + AssertJ. No Spring, no database, and the whole class runs in
 * milliseconds. Business rules live in the domain, so most logic is tested HERE, at the base of the
 * test pyramid.
 *
 * <p>Style: Arrange / Act / Assert, one behavior per test, names that read like specifications,
 * and {@code @Nested} classes to group scenarios.
 */
class OrderTest {

    private static final Address ADDRESS = new Address("1 Main St", "Springfield", "1000", "NL");

    private static Order newOrder(DiscountPolicy policy, LineDraft... lines) {
        return Order.place(UUID.randomUUID(), "alice", ADDRESS, List.of(lines), policy, "{}");
    }

    @Nested
    @DisplayName("placing an order")
    class Placing {

        @Test
        void computesTotalFromLinesAndStartsPending() {
            Order order = newOrder(new DiscountPolicy.None(),
                    new LineDraft("SKU-1", 2, Money.of("10.00", "EUR")),
                    new LineDraft("SKU-2", 1, Money.of("5.50", "EUR")));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getTotal()).isEqualTo(Money.of("25.50", "EUR"));
            assertThat(order.getLines()).extracting(OrderLine::getLineNo).containsExactly((short) 1, (short) 2);
        }

        @Test
        void appliesTheDiscountPolicy() {
            Order order = newOrder(new DiscountPolicy.PercentageOff(10), new LineDraft("SKU-1", 1, Money.of("200.00", "EUR")));

            assertThat(order.getTotal()).isEqualTo(Money.of("180.00", "EUR"));
            assertThat(order.getDiscountAmount()).isEqualByComparingTo("20.00");
        }

        @Test
        void rejectsMixedCurrencies() {
            assertThatThrownBy(() -> newOrder(new DiscountPolicy.None(),
                    new LineDraft("SKU-1", 1, Money.of("1", "EUR")),
                    new LineDraft("SKU-2", 1, Money.of("1", "USD"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same currency");
        }

        @Test
        void exposesLinesReadOnly() {
            Order order = newOrder(new DiscountPolicy.None(), new LineDraft("SKU-1", 1, Money.of("1", "EUR")));
            assertThatThrownBy(() -> order.getLines().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("state machine")
    class StateMachine {

        /** One parameterized test documents the whole transition table. */
        @ParameterizedTest(name = "{0} -> {1} allowed: {2}")
        @CsvSource({
                "PENDING,   CONFIRMED, true",
                "PENDING,   CANCELLED, true",
                "PENDING,   SHIPPED,   false",
                "CONFIRMED, SHIPPED,   true",
                "SHIPPED,   CANCELLED, false",
                "DELIVERED, CANCELLED, false",
                "CANCELLED, CONFIRMED, false"
        })
        void transitionTable(OrderStatus from, OrderStatus to, boolean allowed) {
            assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
        }

        @Test
        void cancellingAShippedOrderFails() {
            Order order = newOrder(new DiscountPolicy.None(), new LineDraft("SKU-1", 1, Money.of("1", "EUR")));
            order.confirm();
            order.ship();

            assertThatThrownBy(() -> order.cancel("changed my mind"))
                    .isInstanceOf(IllegalOrderStateException.class)
                    .hasMessageContaining("SHIPPED");
        }

        @Test
        void cancellationRequiresAReason() {
            Order order = newOrder(new DiscountPolicy.None(), new LineDraft("SKU-1", 1, Money.of("1", "EUR")));
            assertThatThrownBy(() -> order.cancel(" ")).isInstanceOf(IllegalArgumentException.class);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);     // unchanged after a rejected command
        }
    }

    @Test
    void entityEqualityIsBasedOnIdentity() {
        Order a = newOrder(new DiscountPolicy.None(), new LineDraft("SKU-1", 1, Money.of("1", "EUR")));
        Order b = newOrder(new DiscountPolicy.None(), new LineDraft("SKU-1", 1, Money.of("1", "EUR")));
        assertThat(a).isNotEqualTo(b).isEqualTo(a);
    }
}
