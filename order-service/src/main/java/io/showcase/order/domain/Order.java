package io.showcase.order.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.AbstractAggregateRoot;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The ORDER AGGREGATE ROOT (Domain-Driven Design).
 *
 * <h2>Rich domain model, not an anemic one</h2>
 * There are no public setters. State changes only through intention-revealing methods
 * ({@link #confirm()}, {@link #cancel(String)}, {@link #ship()}) that enforce the invariants. The
 * aggregate is the CONSISTENCY BOUNDARY: order lines are only modified through the Order, and one
 * aggregate is saved per transaction.
 *
 * <h2>JPA details worth knowing</h2>
 * <ul>
 *   <li>{@code @Version}: OPTIMISTIC LOCKING. Hibernate adds {@code WHERE version = ?} to every UPDATE
 *       and increments it. If two transactions loaded the same version, the second UPDATE affects 0 rows
 *       and Spring throws {@code ObjectOptimisticLockingFailureException}. That's ideal for low-contention
 *       data: there are no DB locks while the user "thinks".</li>
 *   <li>{@code @OneToMany(mappedBy, cascade = ALL, orphanRemoval = true)}: lines are PART OF the order.
 *       Saving the order saves its lines, and removing a line from the list deletes its row.
 *       {@code @OneToMany} is LAZY by default; fetch it explicitly with an {@code @EntityGraph} where
 *       needed (OrderRepository) to avoid the N+1 query problem.</li>
 *   <li>Auditing: {@code @CreatedDate/@LastModifiedDate/@CreatedBy} are filled in by Spring Data's
 *       {@link AuditingEntityListener} (enabled in PersistenceConfig, with the user taken from the
 *       SecurityContext).</li>
 *   <li>{@link AbstractAggregateRoot}: {@code registerEvent(...)} collects DOMAIN EVENTS that Spring Data
 *       publishes through the ApplicationEventPublisher when {@code repository.save(order)} is called.
 *       Listeners can then react BEFORE or AFTER the commit ({@code @TransactionalEventListener}).</li>
 * </ul>
 */
@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
public class Order extends AbstractAggregateRoot<Order> {

    @Id
    private UUID id;

    @Version
    private long version;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)   // store "PENDING", never the ordinal: reordering the enum must not corrupt data
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "total_amount", nullable = false))
    @AttributeOverride(name = "currency", column = @Column(name = "currency", nullable = false, length = 3))
    private Money total;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;

    @Embedded
    private Address shippingAddress;

    @Column(name = "cancellation_reason")
    private @Nullable String cancellationReason;

    /**
     * JSONB column mapped as a String. The column transformer casts the bound parameter to jsonb on write
     * ({@code ?::jsonb}). That's portable across Hibernate versions and doesn't need a JSON FormatMapper.
     * Queries on it are native SQL using the GIN index (OrderReportingRepository).
     */
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String metadataJson = "{}";

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<OrderLine> lines = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private @Nullable String createdBy;

    /** Required by JPA (it instantiates entities reflectively). {@code protected} keeps application code from using it. */
    @SuppressWarnings("NullAway.Init")
    protected Order() {
    }

    // =====================================================================================
    // FACTORY: the only way to create a valid order
    // =====================================================================================

    /**
     * Static factory method (rather than a public constructor): a meaningful name, validation in
     * one place, and room to return cached or subclass instances later.
     */
    public static Order place(UUID id, String customerId, Address shippingAddress,
                              List<LineDraft> drafts, DiscountPolicy discountPolicy, String metadataJson) {
        Objects.requireNonNull(id, "id");
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        String currency = drafts.getFirst().unitPrice().currency();    // Java 21 SequencedCollection.getFirst()
        if (drafts.stream().anyMatch(d -> !d.unitPrice().currency().equals(currency))) {
            throw new IllegalArgumentException("All lines must use the same currency");
        }

        var order = new Order();
        order.id = id;
        order.customerId = customerId;
        order.shippingAddress = shippingAddress;
        order.metadataJson = metadataJson;
        order.status = OrderStatus.PENDING;

        short lineNo = 1;
        for (LineDraft draft : drafts) {
            order.lines.add(new OrderLine(order, lineNo++, draft.sku(), draft.quantity(), draft.unitPrice()));
        }

        Money subtotal = drafts.stream().map(LineDraft::lineTotal).reduce(Money.zero(currency), Money::plus);
        Money discount = discountPolicy.discountFor(drafts, subtotal);
        order.discountAmount = discount.amount();
        order.total = subtotal.minus(discount);

        order.registerEvent(new OrderStatusChanged(id, customerId, null, OrderStatus.PENDING, order.total));
        return order;
    }

    // =====================================================================================
    // BEHAVIOUR: state transitions guarded by the state machine
    // =====================================================================================

    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
    }

    public void ship() {
        transitionTo(OrderStatus.SHIPPED);
    }

    public void markDelivered() {
        transitionTo(OrderStatus.DELIVERED);
    }

    public void cancel(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A cancellation reason is required");
        }
        transitionTo(OrderStatus.CANCELLED);
        this.cancellationReason = reason;
    }

    private void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalOrderStateException(id, status, next);
        }
        OrderStatus previous = status;
        status = next;
        registerEvent(new OrderStatusChanged(id, customerId, previous, next, total));
    }

    // =====================================================================================
    // Read access (no setters!)
    // =====================================================================================

    public UUID getId() { return id; }
    public long getVersion() { return version; }
    public String getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public Money getTotal() { return total; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public Address getShippingAddress() { return shippingAddress; }
    public @Nullable String getCancellationReason() { return cancellationReason; }
    public String getMetadataJson() { return metadataJson; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public @Nullable Instant getUpdatedAt() { return updatedAt; }
    public @Nullable String getCreatedBy() { return createdBy; }

    /** Read-only view: callers can't bypass the aggregate's invariants by mutating the list. */
    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    /**
     * Entity equality = identity (the id), NOT field values. Two loaded copies of the same row are
     * "the same order". The constant hashCode keeps it stable for entities whose id is assigned
     * late. Here the id is assigned in the factory, but the pattern is the safe JPA default.
     */
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Order other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return Order.class.hashCode();
    }

    @Override
    public String toString() {
        // Never include lazy collections in toString(): logging would trigger a query or a LazyInitializationException
        return "Order[id=%s, status=%s, total=%s]".formatted(id, status, total);
    }
}
