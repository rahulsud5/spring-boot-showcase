package io.showcase.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Table-driven tests with {@code @MethodSource}: each sealed variant and guard branch is covered once. */
class DiscountPolicyTest {

    private static final List<LineDraft> LINES = List.of(
            new LineDraft("SKU-COFFEE", 3, Money.of("10.00", "EUR")),
            new LineDraft("SKU-MUG", 1, Money.of("20.00", "EUR")));
    private static final Money SUBTOTAL = Money.of("50.00", "EUR");

    static Stream<Arguments> policies() {
        return Stream.of(
                Arguments.of(new DiscountPolicy.None(), "0.00"),
                Arguments.of(new DiscountPolicy.PercentageOff(10), "5.00"),
                Arguments.of(new DiscountPolicy.BuyXGetYFree("SKU-COFFEE", 2, 1), "10.00"),   // 3 coffees: 1 free
                Arguments.of(new DiscountPolicy.LoyaltyTier("GOLD", 7), "7.50"),             // 15%
                Arguments.of(new DiscountPolicy.LoyaltyTier("GOLD", 1), "5.00"),             // 10%
                Arguments.of(new DiscountPolicy.LoyaltyTier("SILVER", 3), "2.50"),           // 5%
                Arguments.of(new DiscountPolicy.LoyaltyTier("STANDARD", 9), "0.00"));
    }

    @ParameterizedTest
    @MethodSource("policies")
    void computesDiscount(DiscountPolicy policy, String expected) {
        assertThat(policy.discountFor(LINES, SUBTOTAL)).isEqualTo(Money.of(expected, "EUR"));
    }

    @Test
    void percentageMustBeInRange() {
        assertThatThrownBy(() -> new DiscountPolicy.PercentageOff(95)).isInstanceOf(IllegalArgumentException.class);
    }
}
