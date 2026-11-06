package io.showcase.order.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * A line of an order: an entity INSIDE the Order aggregate (no repository of its own).
 *
 * <p>ID generation: a SEQUENCE with {@code allocationSize = 50} matching {@code INCREMENT BY 50} in V1.
 * Hibernate's pooled optimizer hands out 50 ids per database round trip, which also lets
 * {@code hibernate.jdbc.batch_size} batch the INSERTs. With {@code GenerationType.IDENTITY}, Hibernate
 * must execute every INSERT immediately to read the generated key, so batching is disabled.
 *
 * <p>{@code @ManyToOne(fetch = LAZY)}: JPA's default for {@code @ManyToOne} is EAGER. That's a classic
 * performance trap, so always make it LAZY explicitly.
 */
@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_lines_seq")
    @SequenceGenerator(name = "order_lines_seq", sequenceName = "order_lines_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "unit_amount", nullable = false))
    @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    private Money unitPrice;

    @SuppressWarnings("NullAway.Init")
    protected OrderLine() {
    }

    OrderLine(Order order, short lineNo, String sku, int quantity, Money unitPrice) {   // package-private: only Order creates lines
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.order = order;
        this.lineNo = lineNo;
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }

    public Long getId() { return id; }
    public short getLineNo() { return lineNo; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public Money getUnitPrice() { return unitPrice; }
}
