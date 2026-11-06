package io.showcase.order.persistence;

import io.showcase.order.domain.Order;
import io.showcase.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * SPRING DATA JPA REPOSITORY: an interface only. Spring generates the implementation at startup.
 *
 * <p>The query styles demonstrated, from simplest to most powerful:
 * <ol>
 *   <li><b>Inherited CRUD</b> from JpaRepository: save, findById, findAll(Pageable)...</li>
 *   <li><b>Derived queries</b>: parsed from the METHOD NAME
 *       ({@code findByCustomerIdOrderByCreatedAtDescIdDesc}).</li>
 *   <li><b>{@code @Query} JPQL</b>: entity-based, portable, validated at startup.</li>
 *   <li><b>DTO projections</b>: {@code select new ...Record(...)} loads ONLY the needed columns,
 *       with no entity tracking.</li>
 *   <li><b>Interface projections</b>: Spring Data generates a proxy exposing selected properties.</li>
 *   <li><b>Native SQL</b>: for PostgreSQL-specific features (JSONB operators, SKIP LOCKED, window
 *       functions). See OrderReportingRepository.</li>
 *   <li><b>Specifications</b> ({@link JpaSpecificationExecutor}): DYNAMIC criteria built at runtime.</li>
 * </ol>
 */
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    /**
     * {@code @EntityGraph}: fetch the order AND its lines in ONE query (a LEFT JOIN). Without it,
     * reading the lazy collection triggers a second query, and for a LIST of N orders that's N extra
     * queries: the infamous N+1 problem.
     */
    @EntityGraph(attributePaths = "lines")
    Optional<Order> findWithLinesById(UUID id);

    /**
     * PESSIMISTIC WRITE lock: {@code SELECT ... FOR UPDATE}. Other transactions trying to lock or
     * update this row WAIT until we commit. The lock timeout hint (3 s) prevents waiting forever; the
     * caller then gets a {@code PessimisticLockingFailureException} it can map to 409/503.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(UUID id);

    /**
     * KEYSET SCROLLING (Spring Data 3.1+): the returned {@link Window} holds the content plus the
     * position of its last element. Passing that position back yields the next window, using
     * {@code WHERE (created_at, id) < (...)} instead of OFFSET. The ORDER BY must be UNIQUE (the id is
     * the tie-breaker), or rows with equal timestamps could be skipped or duplicated.
     */
    Window<Order> findByCustomerIdOrderByCreatedAtDescIdDesc(String customerId, ScrollPosition position, Limit limit);

    /**
     * DTO PROJECTION with a JPQL constructor expression: one aggregated row per status, nothing
     * loaded into the persistence context.
     */
    @Query("""
            select new io.showcase.order.persistence.StatusCount(
                       o.status, count(o), coalesce(sum(o.total.amount), 0))
            from Order o
            where o.createdAt >= :since
            group by o.status
            order by count(o) desc
            """)
    List<StatusCount> countByStatusSince(Instant since);

    /**
     * INTERFACE PROJECTION: Hibernate selects only id + status. Ideal for batch jobs that must
     * not load full aggregates.
     */
    List<OrderIdAndStatus> findTop100ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(OrderStatus status, Instant before);

    interface OrderIdAndStatus {
        UUID getId();
        OrderStatus getStatus();
    }

    /**
     * BULK UPDATE: one SQL statement instead of loading and saving N entities.
     * <b>Caveats</b> (that's why it's used only for housekeeping):
     * <ul>
     *   <li>it BYPASSES the domain model: no state-machine checks, no domain events, no outbox</li>
     *   <li>it bypasses {@code @Version} unless incremented manually (as done here)</li>
     *   <li>the persistence context is stale afterwards, hence {@code clearAutomatically = true}</li>
     * </ul>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order o
               set o.status = io.showcase.order.domain.OrderStatus.CANCELLED,
                   o.cancellationReason = :reason,
                   o.version = o.version + 1,
                   o.updatedAt = :now
             where o.status = io.showcase.order.domain.OrderStatus.PENDING
               and o.createdAt < :cutoff
            """)
    int cancelStalePending(Instant cutoff, String reason, Instant now);

    /**
     * NATIVE query using the JSONB containment operator {@code @>}, which is backed by the GIN index
     * ix_orders_metadata. CAST(:json AS jsonb) binds the parameter safely (no string concatenation, so
     * no SQL injection).
     */
    @Query(value = """
            SELECT * FROM orders
             WHERE metadata @> CAST(:json AS jsonb)
             ORDER BY created_at DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<Order> findByMetadataContaining(String json, int limit);
}
