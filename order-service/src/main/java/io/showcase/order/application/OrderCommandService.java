package io.showcase.order.application;

import io.showcase.contracts.events.OrderEvent;
import io.showcase.contracts.events.UuidV7;
import io.showcase.contracts.messaging.Messaging;
import io.showcase.order.application.OrderViews.OrderDetails;
import io.showcase.order.domain.Address;
import io.showcase.order.domain.DiscountPolicy;
import io.showcase.order.domain.IllegalOrderStateException;
import io.showcase.order.domain.LineDraft;
import io.showcase.order.domain.Order;
import io.showcase.order.domain.OrderNotFoundException;
import io.showcase.order.application.port.IntegrationEventPublisher;
import io.showcase.order.persistence.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * WRITE side of the order use cases: every public method is ONE database transaction.
 *
 * <h2>How {@code @Transactional} works</h2>
 * Spring wraps this bean in a PROXY. A call from ANOTHER bean goes through the proxy, which asks the
 * {@code PlatformTransactionManager} (here {@code JpaTransactionManager}) to begin a transaction,
 * binds the JDBC connection and EntityManager to the current THREAD, invokes the method, then
 * commits, or rolls back on an exception.
 *
 * <h2>Rules encoded here (see docs/04-transactions.md)</h2>
 * <ul>
 *   <li><b>Class-level default:</b> {@code @Transactional} on the class gives every public method
 *       REQUIRED propagation; methods override attributes where needed.</li>
 *   <li><b>Rollback rules:</b> unchecked exceptions roll back; CHECKED exceptions COMMIT unless
 *       {@code rollbackFor} says otherwise. Our domain exceptions are unchecked on purpose.</li>
 *   <li><b>Self-invocation pitfall:</b> {@code this.confirm(...)} from inside this class BYPASSES the
 *       proxy, so its {@code @Transactional} attributes are silently ignored. Call through another
 *       bean, or design methods so it doesn't matter.</li>
 *   <li><b>No remote calls inside transactions:</b> gRPC and HTTP calls happen in
 *       OrderApplicationService BEFORE we get here. A slow remote call inside a transaction holds a
 *       pooled DB connection and row locks for its whole duration.</li>
 *   <li><b>Dirty checking:</b> a managed entity modified inside a transaction is flushed at commit
 *       automatically. The explicit {@code save()} calls are there to publish the aggregate's domain
 *       events (AbstractAggregateRoot only publishes on save).</li>
 * </ul>
 */
@Service
@Transactional
public class OrderCommandService {

    private final OrderRepository orders;
    private final IntegrationEventPublisher events;
    private final AuditService audit;

    public OrderCommandService(OrderRepository orders, IntegrationEventPublisher events, AuditService audit) {
        this.orders = orders;
        this.events = events;
        this.audit = audit;
    }

    /**
     * ONE ACID transaction: INSERT order + INSERT lines + INSERT outbox row. Either all of them commit
     * or none do. That's what makes the event publication reliable (transactional outbox).
     */
    public OrderDetails createOrder(String customerId, Address address, List<LineDraft> drafts,
                                    DiscountPolicy discount, String metadataJson) {
        Order order = Order.place(UuidV7.next(), customerId, address, drafts, discount, metadataJson);
        orders.save(order);

        events.publish(Messaging.Topics.ORDER_EVENTS, new OrderEvent.OrderPlaced(
                order.getId(),
                customerId,
                order.getLines().stream().map(l -> new OrderEvent.OrderPlaced.Line(l.getSku(), l.getQuantity())).toList(),
                order.getTotal().amount(),
                order.getTotal().currency()));

        return OrderDetails.from(order);
    }

    /**
     * Called by the saga when inventory reserved the stock.
     *
     * <p>{@code noRollbackFor}: a stale or duplicate saga event can arrive for an order that was
     * already cancelled. That's an EXPECTED situation, not a failure: we want the surrounding
     * transaction (which also records the message as processed) to COMMIT. Note that the caller
     * (InventoryEventHandler) declares the same rule. In a joined transaction, the FIRST interceptor
     * that sees a rollback-worthy exception marks the whole transaction rollback-only, and the outer
     * commit then fails with {@code UnexpectedRollbackException}.
     */
    @Transactional(noRollbackFor = IllegalOrderStateException.class)
    public void confirmFromSaga(UUID orderId) {
        Order order = load(orderId);
        order.confirm();                                          // throws IllegalOrderStateException if not PENDING
        orders.save(order);
        events.publish(Messaging.Topics.ORDER_EVENTS, new OrderEvent.OrderConfirmed(orderId));
    }

    /**
     * PESSIMISTIC locking ({@code SELECT ... FOR UPDATE}): cancellation races with saga confirmation.
     * Locking the row serializes the two writers, so the second one sees the committed state and fails
     * cleanly. Use it when conflicts are LIKELY and retrying is expensive; otherwise prefer optimistic
     * locking (see {@link #ship(UUID)}).
     *
     * <p>{@code timeout = 5}: the transaction is rolled back if it takes longer than 5 seconds.
     * Combined with the lock timeout hint on the query, a stuck lock can't pile up threads.
     */
    @Transactional(timeout = 5, noRollbackFor = IllegalOrderStateException.class)
    public OrderDetails cancel(UUID orderId, String reason, String actor) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        try {
            order.cancel(reason);
        } catch (IllegalOrderStateException e) {
            // REQUIRES_NEW: this audit row commits even though the caller receives an error
            audit.record("ORDER_CANCEL_REJECTED", actor, Map.of("orderId", orderId.toString(), "status", order.getStatus().name()));
            throw e;
        }
        orders.save(order);
        events.publish(Messaging.Topics.ORDER_EVENTS, new OrderEvent.OrderCancelled(orderId, reason));
        audit.record("ORDER_CANCELLED", actor, Map.of("orderId", orderId.toString(), "reason", reason));
        return OrderDetails.from(order);
    }

    /**
     * OPTIMISTIC locking: no lock is taken. If another transaction changed the order since we read it,
     * the version check fails at flush time with {@code ObjectOptimisticLockingFailureException}, and
     * OrderApplicationService retries the WHOLE transaction ({@code @Retryable}).
     *
     * <p>{@code isolation = READ_COMMITTED} is PostgreSQL's default and is shown explicitly here.
     * REPEATABLE_READ or SERIALIZABLE give stronger guarantees but raise serialization failures that
     * must also be retried.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDetails ship(UUID orderId) {
        Order order = load(orderId);
        order.ship();
        orders.saveAndFlush(order);     // flush NOW so a version conflict surfaces inside this method
        return OrderDetails.from(order);
    }

    private Order load(UUID orderId) {
        return orders.findWithLinesById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
