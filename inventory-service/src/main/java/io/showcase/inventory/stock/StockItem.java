package io.showcase.inventory.stock;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Stock level per SKU, in its OWN collection (not embedded in Product): it's a hot, frequently
 * updated value with a different access pattern than the catalog.
 *
 * <p>Invariant: {@code available >= 0}. It's enforced by ATOMIC CONDITIONAL UPDATES in StockService
 * (decrement only if enough is available), not by read-check-write in Java, which would race.
 */
@Document("stock")
public class StockItem {

    @Id
    private String sku;
    private int available;
    private int reserved;
    @Version
    private Long version;

    protected StockItem() {
    }

    public StockItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
    }

    public String getSku() { return sku; }
    public int getAvailable() { return available; }
    public int getReserved() { return reserved; }
}
