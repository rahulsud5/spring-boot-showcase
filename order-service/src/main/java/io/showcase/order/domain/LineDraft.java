package io.showcase.order.domain;

/** Input for creating an order line: a SKU, a quantity and the price resolved from the catalog. */
public record LineDraft(String sku, int quantity, Money unitPrice) {

    public LineDraft {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
