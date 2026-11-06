package io.showcase.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure JUnit 5 tests: no Spring, so they run in milliseconds. That's the base of the test pyramid. */
class EventTypesTest {

    @Test
    void registryDiscoversEveryLeafOfTheSealedHierarchy() {
        assertThat(EventTypes.knownTypes()).containsExactlyInAnyOrder(
                "OrderPlaced", "OrderConfirmed", "OrderCancelled",
                "StockReserved", "StockRejected", "StockReleased");
        assertThat(EventTypes.classOf("StockReserved")).contains(InventoryEvent.StockReserved.class);
        assertThat(EventTypes.classOf("java.lang.Runtime")).isEmpty();   // unknown types are never deserialized
    }

    @Test
    void envelopeDerivesTypeAndAggregateFromPayload() {
        var orderId = UUID.randomUUID();
        var envelope = EventEnvelope.wrap(new OrderEvent.OrderConfirmed(orderId), Instant.EPOCH);

        assertThat(envelope.eventType()).isEqualTo("OrderConfirmed");
        assertThat(envelope.aggregateId()).isEqualTo(orderId.toString());
        assertThat(envelope.eventId().version()).isEqualTo(7);
    }

    @Test
    void compactConstructorsRejectInvalidEvents() {
        assertThatThrownBy(() -> new OrderEvent.OrderPlaced(UUID.randomUUID(), "c1", List.of(), BigDecimal.TEN, "EUR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderEvent.OrderPlaced.Line("SKU-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uuidV7IsTimeOrdered() throws InterruptedException {
        UUID first = UuidV7.next();
        Thread.sleep(2);
        UUID second = UuidV7.next();

        assertThat(second.compareTo(first)).isPositive();
        assertThat(UuidV7.timestampMillis(second)).isGreaterThanOrEqualTo(UuidV7.timestampMillis(first));
    }
}
