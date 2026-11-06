package io.showcase.inventory.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.showcase.contracts.inventory.v1.CheckAvailabilityRequest;
import io.showcase.contracts.inventory.v1.CheckAvailabilityResponse;
import io.showcase.contracts.inventory.v1.LineItem;
import io.showcase.inventory.stock.ReservationFacade;
import io.showcase.inventory.stock.StockItem;
import io.showcase.inventory.stock.StockService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test of a gRPC service: no server, no network. A tiny recording {@link StreamObserver}
 * captures what the service emits (onNext / onError / onCompleted), exactly what a client would receive.
 */
class InventoryGrpcServiceTest {

    private final StockService stock = mock(StockService.class);
    private final InventoryGrpcService service = new InventoryGrpcService(stock, mock(ReservationFacade.class), Clock.systemUTC());

    static final class RecordingObserver<T> implements StreamObserver<T> {
        final List<T> values = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override public void onNext(T value) { values.add(value); }
        @Override public void onError(Throwable t) { error = t; }
        @Override public void onCompleted() { completed = true; }
    }

    @Test
    void reportsPerSkuAvailability() {
        given(stock.levels(anyList())).willReturn(Map.of("SKU-1", new StockItem("SKU-1", 5), "SKU-2", new StockItem("SKU-2", 0)));
        var observer = new RecordingObserver<CheckAvailabilityResponse>();

        service.checkAvailability(CheckAvailabilityRequest.newBuilder()
                .addLines(LineItem.newBuilder().setSku("SKU-1").setQuantity(2))
                .addLines(LineItem.newBuilder().setSku("SKU-2").setQuantity(1))
                .build(), observer);

        assertThat(observer.completed).isTrue();
        CheckAvailabilityResponse response = observer.values.getFirst();
        assertThat(response.getAllAvailable()).isFalse();
        assertThat(response.getItemsList()).extracting(i -> i.getSku() + ":" + i.getAvailable())
                .containsExactly("SKU-1:5", "SKU-2:0");
    }

    @Test
    void rejectsEmptyRequestsWithInvalidArgument() {
        var observer = new RecordingObserver<CheckAvailabilityResponse>();

        service.checkAvailability(CheckAvailabilityRequest.getDefaultInstance(), observer);

        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
}
