package io.showcase.inventory.stock;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns "reserve or throw" into a RESULT value. The transactional reserve runs through the
 * StockService PROXY (a call from another bean), so the rollback actually happens, and the rejection is
 * recorded afterwards outside that rolled-back transaction. Used by both the Kafka listener and the
 * gRPC ReserveStock RPC.
 */
@Component
public class ReservationFacade {

    private final StockService stock;

    public ReservationFacade(StockService stock) {
        this.stock = stock;
    }

    public StockService.ReservationResult reserve(String orderId, List<Reservation.Line> lines) {
        try {
            return stock.reserveAll(orderId, lines);
        } catch (StockService.InsufficientStockException e) {
            stock.recordRejection(orderId, lines, e.skus);
            return new StockService.ReservationResult.Rejected(e.skus);
        }
    }
}
