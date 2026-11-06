package io.showcase.inventory.messaging;

import io.showcase.contracts.events.EventEnvelope;
import io.showcase.contracts.events.EventTypes;
import io.showcase.contracts.events.IntegrationEvent;
import io.showcase.contracts.events.InventoryEvent;
import io.showcase.contracts.events.OrderEvent;
import io.showcase.contracts.messaging.Messaging.Topics;
import io.showcase.inventory.stock.Reservation;
import io.showcase.inventory.stock.ReservationFacade;
import io.showcase.inventory.stock.StockService;
import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The inventory side of the saga: CONSUME OrderPlaced/OrderCancelled → reserve/release stock →
 * PRODUCE StockReserved/StockRejected/StockReleased.
 *
 * <h2>Exactly-once semantics (EOS) for consume-transform-produce</h2>
 * With {@code spring.kafka.producer.transaction-id-prefix} set (application.yml), Spring Boot
 * auto-configures a {@code KafkaTransactionManager} and the listener container runs each record in a
 * KAFKA TRANSACTION:
 * <pre>
 *   begin tx → poll record → produce reply(s) → send consumer offsets to the tx → commit tx
 * </pre>
 * The reply AND the consumed offset commit ATOMICALLY: either both are visible, or neither is
 * (consumers use isolation.level=read_committed). A crash mid-way means the record is re-processed,
 * but no duplicate reply becomes visible.
 *
 * <p><b>The honest caveat:</b> Kafka transactions cover KAFKA only. The MongoDB writes are NOT part of
 * them, so a retry may re-run the reservation logic. That's why the reservation is idempotent (orderId
 * as _id). "Exactly-once" end to end is always EOS within Kafka plus idempotent side effects.
 */
@Component
class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final ReservationFacade reservations;
    private final StockService stock;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Clock clock;

    OrderEventsListener(ReservationFacade reservations, StockService stock, KafkaTemplate<String, String> kafka,
                        JsonMapper json, Clock clock) {
        this.reservations = reservations;
        this.stock = stock;
        this.kafka = kafka;
        this.json = json;
        this.clock = clock;
    }

    @KafkaListener(id = "inventory-order-events", topics = Topics.ORDER_EVENTS, groupId = "inventory-service")
    void onOrderEvent(ConsumerRecord<String, String> record) {
        JsonNode envelope = json.readTree(record.value());
        String type = envelope.required("eventType").asString();
        IntegrationEvent event = json.treeToValue(envelope.required("payload"),
                EventTypes.classOf(type).orElseThrow(() -> new IllegalArgumentException("Unknown event type " + type)));

        switch (event) {
            case OrderEvent.OrderPlaced placed -> handlePlaced(placed);
            case OrderEvent.OrderCancelled cancelled -> {
                if (stock.release(cancelled.orderId().toString())) {
                    publish(new InventoryEvent.StockReleased(cancelled.orderId()));
                }
            }
            case OrderEvent.OrderConfirmed confirmed -> log.debug("Order {} confirmed", confirmed.orderId());
            case InventoryEvent own -> log.warn("Unexpected inventory event on the order topic: {}", own);
        }
    }

    private void handlePlaced(OrderEvent.OrderPlaced placed) {
        List<Reservation.Line> lines = placed.lines().stream()
                .map(l -> new Reservation.Line(l.sku(), l.quantity())).toList();

        InventoryEvent reply = switch (reservations.reserve(placed.orderId().toString(), lines)) {
            case StockService.ReservationResult.Reserved(String reservationId) ->
                    new InventoryEvent.StockReserved(placed.orderId(), reservationId);
            case StockService.ReservationResult.Rejected(List<String> skus) ->
                    new InventoryEvent.StockRejected(placed.orderId(), "insufficient stock", skus);
        };
        publish(reply);
    }

    /** Sent INSIDE the listener's Kafka transaction: visible only if the whole record commits. */
    private void publish(InventoryEvent event) {
        var envelope = EventEnvelope.wrap(event, clock.instant());
        kafka.send(Topics.INVENTORY_EVENTS, envelope.aggregateId(), json.writeValueAsString(envelope));
        log.info("Published {} for order {}", envelope.eventType(), envelope.aggregateId());
    }
}
