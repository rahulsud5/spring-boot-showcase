package io.showcase.order.messaging.kafka;

import io.showcase.contracts.events.IntegrationEvent;
import io.showcase.contracts.events.InventoryEvent;
import io.showcase.contracts.events.OrderEvent;
import io.showcase.order.application.OrderCommandService;
import io.showcase.order.domain.IllegalOrderStateException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga step handler: an IDEMPOTENT consumer plus exhaustive event handling.
 *
 * <p>Within ONE transaction:
 * <ol>
 *   <li>record the message id in processed_messages (a duplicate means skip it);</li>
 *   <li>apply the business change (confirm or cancel the order) and write follow-up outbox events.</li>
 * </ol>
 * If step 2 fails, step 1 rolls back too, so a redelivery will be processed again. Both steps commit
 * together: "effectively once".
 *
 * <p>{@code noRollbackFor = IllegalOrderStateException.class}: a saga reply for an order that has moved
 * on (for example, the customer cancelled first) is NORMAL. We log it and still commit the dedupe
 * record. See OrderCommandService.confirmFromSaga for why BOTH methods need this rule.
 */
@Service
public class InventoryEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventHandler.class);
    static final String CONSUMER = "order-service.inventory-events";

    private final ProcessedMessageStore processedMessages;
    private final OrderCommandService commands;

    public InventoryEventHandler(ProcessedMessageStore processedMessages, OrderCommandService commands) {
        this.processedMessages = processedMessages;
        this.commands = commands;
    }

    @Transactional(noRollbackFor = IllegalOrderStateException.class)
    public void handle(UUID eventId, IntegrationEvent event) {
        if (!processedMessages.markProcessed(CONSUMER, eventId)) {
            log.info("Duplicate event {} ignored", eventId);
            return;
        }
        try {
            // EXHAUSTIVE switch over the SEALED IntegrationEvent hierarchy: no default branch.
            // If the contracts module adds an event type, this stops compiling until it's handled.
            switch (event) {
                case InventoryEvent.StockReserved(UUID orderId, String reservationId) -> {
                    log.info("Stock reserved for {} (reservation {})", orderId, reservationId);
                    commands.confirmFromSaga(orderId);
                }
                case InventoryEvent.StockRejected(UUID orderId, String reason, var skus) ->
                        commands.cancel(orderId, "Out of stock: " + String.join(", ", skus) + " (" + reason + ")", "inventory-service");
                case InventoryEvent.StockReleased released ->
                        log.info("Stock released for cancelled order {}", released.orderId());
                case OrderEvent own ->
                        log.warn("Ignoring our own event type {} on the inventory topic", own.getClass().getSimpleName());
            }
        } catch (IllegalOrderStateException stale) {
            log.warn("Stale saga event {} ignored: {}", eventId, stale.getMessage());
        }
    }
}
