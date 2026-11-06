package io.showcase.order.messaging.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.showcase.contracts.events.InventoryEvent;
import io.showcase.order.application.OrderCommandService;
import io.showcase.order.domain.IllegalOrderStateException;
import io.showcase.order.domain.OrderStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UNIT test of the saga handler with Mockito: the business decisions are verified without Kafka or a
 * database. The listener class is a thin transport adapter, so this is where the logic worth testing
 * lives. (The full Kafka round trip is covered by OrderFlowIT.)
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventHandlerTest {

    @Mock
    ProcessedMessageStore processed;
    @Mock
    OrderCommandService commands;
    @InjectMocks
    InventoryEventHandler handler;

    private final UUID eventId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @Test
    void confirmsTheOrderWhenStockIsReserved() {
        given(processed.markProcessed(InventoryEventHandler.CONSUMER, eventId)).willReturn(true);

        handler.handle(eventId, new InventoryEvent.StockReserved(orderId, "res-1"));

        verify(commands).confirmFromSaga(orderId);
    }

    @Test
    void cancelsTheOrderWhenStockIsRejected() {
        given(processed.markProcessed(InventoryEventHandler.CONSUMER, eventId)).willReturn(true);

        handler.handle(eventId, new InventoryEvent.StockRejected(orderId, "insufficient", List.of("SKU-1")));

        verify(commands).cancel(any(), anyString(), any());
    }

    @Test
    void ignoresDuplicateDeliveries() {
        given(processed.markProcessed(InventoryEventHandler.CONSUMER, eventId)).willReturn(false);

        handler.handle(eventId, new InventoryEvent.StockReserved(orderId, "res-1"));

        verify(commands, never()).confirmFromSaga(any());
    }

    @Test
    void swallowsStaleEventsForOrdersThatMovedOn() {
        given(processed.markProcessed(InventoryEventHandler.CONSUMER, eventId)).willReturn(true);
        willThrow(new IllegalOrderStateException(orderId, OrderStatus.CANCELLED, OrderStatus.CONFIRMED))
                .given(commands).confirmFromSaga(orderId);

        handler.handle(eventId, new InventoryEvent.StockReserved(orderId, "res-1"));   // must not throw
    }
}
