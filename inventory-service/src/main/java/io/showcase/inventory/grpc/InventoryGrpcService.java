package io.showcase.inventory.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.showcase.contracts.inventory.v1.CheckAvailabilityRequest;
import io.showcase.contracts.inventory.v1.CheckAvailabilityResponse;
import io.showcase.contracts.inventory.v1.InventoryServiceGrpc;
import io.showcase.contracts.inventory.v1.LineItem;
import io.showcase.contracts.inventory.v1.ReserveStockRequest;
import io.showcase.contracts.inventory.v1.ReserveStockResponse;
import io.showcase.contracts.inventory.v1.SkuAvailability;
import io.showcase.contracts.inventory.v1.StockLevel;
import io.showcase.contracts.inventory.v1.StreamStockLevelsRequest;
import io.showcase.inventory.stock.Reservation;
import io.showcase.inventory.stock.StockItem;
import io.showcase.inventory.stock.StockService;
import io.showcase.inventory.stock.ReservationFacade;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC SERVER implementation. It extends the generated {@code ImplBase} and overrides the RPCs.
 *
 * <p>{@code @GrpcService} (Spring gRPC, auto-configured by Boot 4.1's spring-boot-starter-grpc-server)
 * registers it with the Netty gRPC server on port 9090, alongside the standard health service
 * (grpc.health.v1, for Kubernetes gRPC probes) and server REFLECTION, which lets
 * {@code grpcurl -plaintext localhost:9090 list} discover services without the .proto file.
 *
 * <p>Error handling: map failures to gRPC STATUS CODES (INVALID_ARGUMENT, NOT_FOUND, UNAVAILABLE...).
 * The client's retry and circuit-breaker logic depends on them, just like HTTP status codes in REST.
 */
@GrpcService
public class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final StockService stock;
    private final ReservationFacade reservations;
    private final Clock clock;

    public InventoryGrpcService(StockService stock, ReservationFacade reservations, Clock clock) {
        this.stock = stock;
        this.reservations = reservations;
        this.clock = clock;
    }

    /** UNARY RPC: one request, one response. */
    @Override
    public void checkAvailability(CheckAvailabilityRequest request, StreamObserver<CheckAvailabilityResponse> responses) {
        if (request.getLinesCount() == 0) {
            responses.onError(Status.INVALID_ARGUMENT.withDescription("at least one line is required").asRuntimeException());
            return;
        }
        Map<String, StockItem> levels = stock.levels(request.getLinesList().stream().map(LineItem::getSku).toList());

        var response = CheckAvailabilityResponse.newBuilder();
        boolean all = true;
        for (LineItem line : request.getLinesList()) {
            int available = levels.containsKey(line.getSku()) ? levels.get(line.getSku()).getAvailable() : 0;
            all &= available >= line.getQuantity();
            response.addItems(SkuAvailability.newBuilder()
                    .setSku(line.getSku()).setRequested(line.getQuantity()).setAvailable(available));
        }
        responses.onNext(response.setAllAvailable(all).build());
        responses.onCompleted();
    }

    @Override
    public void reserveStock(ReserveStockRequest request, StreamObserver<ReserveStockResponse> responses) {
        List<Reservation.Line> lines = request.getLinesList().stream()
                .map(l -> new Reservation.Line(l.getSku(), l.getQuantity())).toList();

        var builder = ReserveStockResponse.newBuilder();
        switch (reservations.reserve(request.getOrderId(), lines)) {
            case StockService.ReservationResult.Reserved(String id) ->
                    builder.setStatus(ReserveStockResponse.Status.RESERVED).setReservationId(id);
            case StockService.ReservationResult.Rejected(List<String> skus) ->
                    builder.setStatus(ReserveStockResponse.Status.REJECTED).addAllUnavailableSkus(skus);
        }
        responses.onNext(builder.build());
        responses.onCompleted();
    }

    /**
     * SERVER-STREAMING RPC: pushes stock levels every second until the client cancels or 60 s pass.
     * Blocking sleeps are fine because gRPC handlers run on virtual threads (spring.threads.virtual.enabled).
     * {@link ServerCallStreamObserver#isCancelled()} detects a disconnected client, so we stop working for nobody.
     */
    @Override
    public void streamStockLevels(StreamStockLevelsRequest request, StreamObserver<StockLevel> responses) {
        var observer = (ServerCallStreamObserver<StockLevel>) responses;
        try {
            for (int tick = 0; tick < 60 && !observer.isCancelled(); tick++) {
                stock.levels(request.getSkusList()).values().forEach(item -> observer.onNext(StockLevel.newBuilder()
                        .setSku(item.getSku())
                        .setAvailable(item.getAvailable())
                        .setReserved(item.getReserved())
                        .setObservedAtEpochMs(clock.millis())
                        .build()));
                Thread.sleep(1_000);
            }
            if (!observer.isCancelled()) {
                observer.onCompleted();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            observer.onError(Status.CANCELLED.withDescription("server shutting down").asRuntimeException());
        }
    }
}
