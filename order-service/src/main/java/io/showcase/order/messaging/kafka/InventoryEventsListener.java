package io.showcase.order.messaging.kafka;

import io.showcase.contracts.events.EventTypes;
import io.showcase.contracts.events.IntegrationEvent;
import io.showcase.contracts.messaging.Messaging.Topics;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka CONSUMER for the saga replies from inventory-service.
 *
 * <p>{@code @KafkaListener}: Spring creates a listener container that polls the topic, invokes this
 * method per record, and COMMITS OFFSETS after successful processing (ack-mode BATCH by default; see
 * application.yml). Scaling: run up to <i>#partitions</i> instances in the same {@code groupId}, and
 * Kafka assigns each partition to exactly one of them.
 *
 * <p>This class only does TRANSPORT concerns (parse the envelope, look up the type). Business
 * handling is in {@link InventoryEventHandler}, which is transactional and testable without Kafka.
 */
@Component
class InventoryEventsListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventsListener.class);

    private final InventoryEventHandler handler;
    private final JsonMapper json;

    InventoryEventsListener(InventoryEventHandler handler, JsonMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(id = "order-inventory-events", topics = Topics.INVENTORY_EVENTS, groupId = "order-service")
    void onMessage(ConsumerRecord<String, String> record) {
        JsonNode envelope = json.readTree(record.value());
        UUID eventId = UUID.fromString(envelope.required("eventId").asString());
        String eventType = envelope.required("eventType").asString();

        // Whitelisted, sealed-hierarchy-based type lookup: unknown types are rejected, never instantiated
        Class<? extends IntegrationEvent> type = EventTypes.classOf(eventType)
                .orElseThrow(() -> new UnknownEventTypeException(eventType));
        IntegrationEvent event = json.treeToValue(envelope.required("payload"), type);

        log.info("Received {} for order {} (partition {}, offset {})", eventType, record.key(), record.partition(), record.offset());
        handler.handle(eventId, event);
    }
}
