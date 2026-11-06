package io.showcase.order.integration.inventory;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.showcase.contracts.inventory.v1.CheckAvailabilityRequest;
import io.showcase.contracts.inventory.v1.CheckAvailabilityResponse;
import io.showcase.contracts.inventory.v1.InventoryServiceGrpc;
import io.showcase.contracts.inventory.v1.LineItem;
import io.showcase.contracts.inventory.v1.SkuAvailability;
import io.showcase.order.application.port.InventoryPort;
import io.showcase.order.config.AppProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

/**
 * ADAPTER implementing the {@link InventoryPort} over gRPC, guarded by a CIRCUIT BREAKER.
 *
 * <h2>gRPC client essentials</h2>
 * <ul>
 *   <li>{@link GrpcChannelFactory} (Spring gRPC, auto-configured by Spring Boot 4.1) creates a
 *       {@link ManagedChannel}, a long-lived, multiplexed HTTP/2 connection. Create it ONCE and share
 *       it; channels are expensive, stubs are cheap.</li>
 *   <li>A BLOCKING stub is perfectly fine on virtual threads. Async and reactive stubs exist for
 *       streaming.</li>
 *   <li><b>DEADLINES</b>: {@code withDeadlineAfter} bounds the whole call. gRPC propagates the
 *       remaining deadline to downstream calls. Never call without one: the default is "wait forever".</li>
 * </ul>
 *
 * <h2>Circuit breaker states (Resilience4j via Spring Cloud CircuitBreaker)</h2>
 * <pre>
 *  CLOSED ──(failure rate > 50% over 20 calls)──► OPEN ──(after 10 s)──► HALF_OPEN
 *     ▲                                            │ fail fast, call fallback   │ 3 trial calls
 *     └──────────────(trial calls succeed)─────────────────────────────────────┘
 * </pre>
 * While OPEN, calls don't even try the network: they return the fallback in microseconds. That
 * protects inventory-service from load while it recovers, and protects US from piling up threads on a
 * dead dependency (cascading failure).
 */
@Component
class GrpcInventoryAdapter implements InventoryPort {

    private static final Logger log = LoggerFactory.getLogger(GrpcInventoryAdapter.class);

    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;
    private final long deadlineMillis;

    GrpcInventoryAdapter(GrpcChannelFactory channels, CircuitBreakerFactory<?, ?> circuitBreakers, AppProperties properties) {
        ManagedChannel channel = channels.createChannel(properties.inventory().grpcTarget());
        this.stub = InventoryServiceGrpc.newBlockingStub(channel);
        this.circuitBreaker = circuitBreakers.create("inventory");
        this.deadlineMillis = properties.inventory().grpcDeadline().toMillis();
    }

    @Override
    public Availability checkAvailability(List<RequestedItem> items) {
        return circuitBreaker.run(() -> callInventory(items), this::fallback);
    }

    private Availability callInventory(List<RequestedItem> items) {
        CheckAvailabilityRequest request = CheckAvailabilityRequest.newBuilder()
                .addAllLines(items.stream()
                        .map(i -> LineItem.newBuilder().setSku(i.sku()).setQuantity(i.quantity()).build())
                        .toList())
                .build();

        CheckAvailabilityResponse response = stub
                .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                .checkAvailability(request);

        if (response.getAllAvailable()) {
            return new Availability.Available();
        }
        return new Availability.Unavailable(response.getItemsList().stream()
                .filter(item -> item.getAvailable() < item.getRequested())
                .map(SkuAvailability::getSku)
                .toList());
    }

    /**
     * FALLBACK = GRACEFUL DEGRADATION: if inventory is slow or down, don't fail the customer. Accept the
     * order as PENDING; the asynchronous saga (Kafka) reserves or rejects it once inventory recovers.
     * Business errors (INVALID_ARGUMENT) should NOT be masked like this, so we rethrow them.
     */
    private Availability fallback(Throwable error) {
        if (error instanceof StatusRuntimeException sre && sre.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
            throw new IllegalArgumentException(sre.getStatus().getDescription(), sre);
        }
        log.warn("Inventory pre-check unavailable, degrading to async reservation: {}", error.toString());
        return new Availability.Unknown(error.getClass().getSimpleName());
    }
}
