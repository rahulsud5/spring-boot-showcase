package io.showcase.order.domain;

import java.util.List;

/**
 * STRATEGY pattern, the Java 21 way: a SEALED interface of RECORDS plus an exhaustive {@code switch}
 * with RECORD PATTERNS and GUARDS.
 *
 * <p>The classic GoF strategy uses polymorphism: each subclass overrides {@code apply()}. With
 * sealed types and pattern matching, the variants are plain data and the algorithm lives in ONE
 * place. Choose based on which axis changes more often:
 * <ul>
 *   <li>new OPERATIONS over a fixed set of variants: pattern matching (like here; no Visitor needed)</li>
 *   <li>new VARIANTS with a fixed set of operations: classic polymorphism</li>
 * </ul>
 * Since the interface is sealed, adding a variant makes this switch fail to compile until it's handled:
 * the compiler acts as a reviewer.
 */
public sealed interface DiscountPolicy {

    record None() implements DiscountPolicy {}

    record PercentageOff(int percent) implements DiscountPolicy {
        public PercentageOff {
            if (percent < 1 || percent > 90) throw new IllegalArgumentException("percent must be between 1 and 90");
        }
    }

    /** "Buy 2, get 1 free" on one SKU. */
    record BuyXGetYFree(String sku, int buy, int free) implements DiscountPolicy {}

    record LoyaltyTier(String tier, int memberYears) implements DiscountPolicy {}

    default Money discountFor(List<LineDraft> lines, Money subtotal) {
        Money zero = Money.zero(subtotal.currency());
        return switch (this) {
            case None() -> zero;                                              // record pattern with no components
            case PercentageOff(int percent) -> subtotal.percentage(percent); // deconstruction: no getter calls
            case BuyXGetYFree(String sku, int buy, int free) -> lines.stream()
                    .filter(line -> line.sku().equals(sku))
                    .map(line -> line.unitPrice().times((line.quantity() / (buy + free)) * free))
                    .reduce(zero, Money::plus);
            // GUARDED patterns ("when"): checked top to bottom, so put the most specific case first
            case LoyaltyTier(String tier, int years) when tier.equals("GOLD") && years >= 5 -> subtotal.percentage(15);
            case LoyaltyTier(String tier, int years) when tier.equals("GOLD") -> subtotal.percentage(10);
            case LoyaltyTier(var tier, var years) when tier.equals("SILVER") -> subtotal.percentage(5);
            case LoyaltyTier lt -> zero;                                     // unguarded catch-all keeps it exhaustive
        };
    }
}
