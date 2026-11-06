package io.showcase.order.messaging.rabbit;

import io.showcase.contracts.events.UuidV7;
import io.showcase.contracts.messaging.Messaging.Rabbit;
import io.showcase.contracts.messaging.NotificationCommand;
import io.showcase.order.domain.OrderStatus;
import io.showcase.order.domain.OrderStatusChanged;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns DOMAIN events into notification COMMANDS on RabbitMQ.
 *
 * <h2>{@code @TransactionalEventListener(phase = AFTER_COMMIT)}</h2>
 * The Order aggregate registers {@code OrderStatusChanged} INSIDE the transaction. A plain
 * {@code @EventListener} would run immediately, also inside the transaction, so an email could go out
 * for an order whose transaction later ROLLS BACK. AFTER_COMMIT defers the listener until the commit
 * succeeded. Other phases: BEFORE_COMMIT (still inside the transaction; can veto it), AFTER_ROLLBACK,
 * AFTER_COMPLETION.
 *
 * <h2>{@code @Async}</h2>
 * Runs on the task executor (virtual threads, see AsyncConfig), so the HTTP response doesn't wait for
 * the broker.
 *
 * <h2>Trade-off (on purpose)</h2>
 * A crash between the commit and this send LOSES the notification. That's acceptable for emails
 * (best effort), but not for the saga, which is why the saga's events use the transactional outbox. Two
 * reliability levels in one service, chosen consciously per use case.
 */
@Component
class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final JdbcClient jdbc;

    NotificationPublisher(RabbitTemplate rabbit, JsonMapper json, JdbcClient jdbc) {
        this.rabbit = rabbit;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(OrderStatusChanged event) {
        String template = switch (event.to()) {
            case CONFIRMED -> "order-confirmed";
            case CANCELLED -> "order-cancelled";
            case SHIPPED -> "order-shipped";
            case PENDING, DELIVERED -> null;          // nothing to send
        };
        if (template == null) {
            return;
        }

        String email = jdbc.sql("SELECT email FROM customers WHERE id = :id")
                .param("id", event.customerId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (email == null) {
            log.warn("No email address for customer {}", event.customerId());
            return;
        }

        var command = new NotificationCommand(UuidV7.next(), email, template, Map.of(
                "orderId", event.orderId().toString(),
                "total", event.total().toString(),
                "status", event.to().name()));

        Message message = MessageBuilder.withBody(json.writeValueAsBytes(command))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(command.commandId().toString())            // the consumer dedupes on it
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)        // survives a broker restart (with a durable queue)
                .setHeader("x-order-status", OrderStatus.valueOf(event.to().name()).name())
                .build();

        rabbit.send(Rabbit.NOTIFICATIONS_EXCHANGE, Rabbit.emailRoutingKey(template), message);
        log.info("Queued '{}' notification for order {}", template, event.orderId());
    }
}
