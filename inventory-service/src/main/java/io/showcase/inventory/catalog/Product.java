package io.showcase.inventory.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A product DOCUMENT in MongoDB: the same entity a relational model would split across 4+ tables
 * (products, product_attributes, product_tags, variants) lives in ONE document, read and written
 * atomically in one operation.
 *
 * <pre>{@code
 * { "_id": "SKU-0042", "name": "Trail Running Shoe", "category": "shoes",
 *   "price": { "amount": NumberDecimal("89.90"), "currency": "EUR" },
 *   "tags": ["running", "outdoor"],
 *   "attributes": { "color": "red", "sizes": [40, 41, 42], "waterproof": true },   ← differs per category!
 *   "version": 3 }
 * }</pre>
 *
 * <h2>Document modeling rules of thumb</h2>
 * <ul>
 *   <li>EMBED what's read together and bounded in size (price, attributes, tags).</li>
 *   <li>REFERENCE what changes independently or grows without bound. Stock levels live in their
 *       own collection: they're updated thousands of times a day, while products rarely change.</li>
 *   <li>Design for the QUERIES, not for normalization.</li>
 * </ul>
 *
 * <h2>Indexes declared here (created at startup when spring.data.mongodb.auto-index-creation=true)</h2>
 * <ul>
 *   <li>{@code @Indexed category}: single-field index.</li>
 *   <li>{@code @CompoundIndex category + price.amount}: "shoes under 100 EUR, cheapest first". The ESR
 *       rule (Equality, Sort, Range) decides the field order, like column order in SQL.</li>
 *   <li>{@code @TextIndexed name/description}: full-text search with stemming, where name weighs 3x.
 *       Only ONE text index is allowed per collection.</li>
 *   <li>{@code @Indexed tags}: a MULTIKEY index (one entry per array element), for "find by any tag".</li>
 * </ul>
 */
@Document("products")
@CompoundIndex(name = "category_price", def = "{'category': 1, 'price.amount': 1}")
public class Product {

    @Id
    private String sku;                         // natural key as _id: no extra unique index needed

    @TextIndexed(weight = 3)
    private String name;

    @TextIndexed
    private String description;

    @Indexed
    private String category;

    private Price price;

    @Indexed
    private List<String> tags;

    /** SCHEMALESS part: each category has different attributes, with no ALTER TABLE ever. */
    private Map<String, Object> attributes;

    @Version
    private Long version;                       // optimistic locking, as with JPA

    @LastModifiedDate
    private Instant updatedAt;

    protected Product() {
    }

    public Product(String sku, String name, String description, String category, Price price,
                   List<String> tags, Map<String, Object> attributes) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        this.tags = List.copyOf(tags);
        this.attributes = Map.copyOf(attributes);
    }

    /**
     * Money in MongoDB: by default Spring Data stores BigDecimal as a STRING, so range queries and
     * sorting compare text ("100" < "9"!). {@code FieldType.DECIMAL128} stores a real IEEE 754 decimal.
     */
    public record Price(@Field(targetType = FieldType.DECIMAL128) BigDecimal amount, String currency) {}

    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public Price getPrice() { return price; }
    public List<String> getTags() { return tags; }
    public Map<String, Object> getAttributes() { return attributes; }
    public Long getVersion() { return version; }
}
