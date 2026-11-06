package io.showcase.inventory.stock;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock reservations with MongoDB: ATOMIC operations + MULTI-DOCUMENT TRANSACTIONS.
 *
 * <h2>Atomic conditional update (no lost updates, no negative stock)</h2>
 * <pre>
 *   db.stock.findOneAndUpdate(
 *       { _id: "SKU-1", available: { $gte: 2 } },          ← condition checked AND applied atomically
 *       { $inc: { available: -2, reserved: 2, version: 1 } })
 * </pre>
 * Two concurrent orders for the last item: exactly ONE matches the filter, and the other gets
 * {@code null}. A single-document operation in MongoDB is always atomic, which is why document
 * design matters so much.
 *
 * <h2>Multi-document transaction (all lines or nothing)</h2>
 * An order with 3 lines touches 3 stock documents + 1 reservation. {@code @Transactional} (backed by
 * the {@code MongoTransactionManager} in MongoConfig) makes them commit together: if line 3 is
 * unavailable, the decrements of lines 1-2 roll back. That requires a REPLICA SET (see
 * docker-compose.yml), and transactions should stay short and small: they're more expensive in Mongo
 * than in Postgres. If most operations need multi-document transactions, the data is probably better
 * off in a relational database (docs/06-sql-vs-nosql.md).
 */
@Service
public class StockService {

    private final MongoTemplate mongo;
    private final ReservationRepository reservations;
    private final Clock clock;

    public StockService(MongoTemplate mongo, ReservationRepository reservations, Clock clock) {
        this.mongo = mongo;
        this.reservations = reservations;
        this.clock = clock;
    }

    public sealed interface ReservationResult {
        record Reserved(String reservationId) implements ReservationResult {}
        record Rejected(List<String> unavailableSkus) implements ReservationResult {}
    }

    /** Thrown inside the transaction to roll back partial decrements. */
    static class InsufficientStockException extends RuntimeException {
        final List<String> skus;

        InsufficientStockException(List<String> skus) {
            super("Insufficient stock: " + skus);
            this.skus = skus;
        }
    }

    @Transactional
    public ReservationResult.Reserved reserveAll(String orderId, List<Reservation.Line> lines) {
        // Idempotency: a redelivered event for an already-handled order changes nothing
        var existing = reservations.findById(orderId);
        if (existing.isPresent()) {
            return new ReservationResult.Reserved(orderId);
        }

        List<String> unavailable = new ArrayList<>();
        for (Reservation.Line line : lines) {
            StockItem updated = mongo.findAndModify(
                    query(where("_id").is(line.sku()).and("available").gte(line.quantity())),
                    new Update().inc("available", -line.quantity()).inc("reserved", line.quantity()).inc("version", 1),
                    FindAndModifyOptions.options().returnNew(true),
                    StockItem.class);
            if (updated == null) {
                unavailable.add(line.sku());
            }
        }
        if (!unavailable.isEmpty()) {
            throw new InsufficientStockException(unavailable);   // ROLLBACK: undoes the successful decrements above
        }
        reservations.insert(new Reservation(orderId, Reservation.Status.RESERVED, lines, null, clock.instant()));
        return new ReservationResult.Reserved(orderId);
    }

    /** Records a rejection in its own (non-transactional) write after the reserve transaction rolled back. */
    public void recordRejection(String orderId, List<Reservation.Line> lines, List<String> skus) {
        if (!reservations.existsById(orderId)) {
            reservations.insert(new Reservation(orderId, Reservation.Status.REJECTED, lines, "unavailable: " + skus, clock.instant()));
        }
    }

    /** Compensation: give the stock back when an order is cancelled. Idempotent through the status check. */
    @Transactional
    public boolean release(String orderId) {
        var reservation = reservations.findById(orderId).orElse(null);
        if (reservation == null || reservation.status() != Reservation.Status.RESERVED) {
            return false;
        }
        for (Reservation.Line line : reservation.lines()) {
            mongo.updateFirst(query(where("_id").is(line.sku())),
                    new Update().inc("available", line.quantity()).inc("reserved", -line.quantity()).inc("version", 1),
                    StockItem.class);
        }
        reservations.save(reservation.withStatus(Reservation.Status.RELEASED));
        return true;
    }

    public Map<String, StockItem> levels(List<String> skus) {
        return mongo.find(query(where("_id").in(skus)), StockItem.class).stream()
                .collect(Collectors.toMap(StockItem::getSku, Function.identity()));
    }
}
