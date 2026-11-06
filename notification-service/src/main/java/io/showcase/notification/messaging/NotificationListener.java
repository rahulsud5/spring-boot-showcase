package io.showcase.notification.messaging;

import io.showcase.contracts.messaging.Messaging.Rabbit;
import io.showcase.contracts.messaging.NotificationCommand;
import io.showcase.notification.archive.ReceiptArchiver;
import io.showcase.notification.email.EmailSender;
import io.showcase.notification.email.TemplateRenderer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ CONSUMER of notification commands.
 *
 * <h2>Acknowledgements and retries</h2>
 * The listener container acks a message only AFTER this method returns normally. On an exception,
 * the configured retry interceptor (spring.rabbitmq.listener.simple.retry.*) retries in-memory 3
 * times with back-off. Then, because {@code default-requeue-rejected: false}, the message is REJECTED
 * without requeue, so the broker routes it to the dead-letter exchange. There are no infinite
 * redelivery loops.
 *
 * <h2>Idempotency</h2>
 * RabbitMQ delivers at least once (a crash after sending the email but before the ack means
 * redelivery). We skip command ids we've already handled. This demo keeps them in a bounded in-memory
 * set; production would use Redis or a DB table (as order-service does) so that all instances share it.
 *
 * <h2>Competing consumers</h2>
 * Run N instances (or set concurrency) and RabbitMQ distributes messages round-robin among them,
 * limited by the prefetch count. That's the work-queue pattern Kafka handles less naturally (see
 * docs/07-messaging-kafka-vs-rabbitmq.md).
 */
@Component
class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final JsonMapper json;
    private final TemplateRenderer templates;
    private final EmailSender email;
    private final ReceiptArchiver archiver;
    private final Set<UUID> recentlyHandled = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > 10_000;       // bounded LRU: no unbounded memory growth
                }
            }));

    NotificationListener(JsonMapper json, TemplateRenderer templates, EmailSender email, ReceiptArchiver archiver) {
        this.json = json;
        this.templates = templates;
        this.email = email;
        this.archiver = archiver;
    }

    @RabbitListener(queues = Rabbit.EMAIL_QUEUE, concurrency = "2-4")
    void onNotification(Message message) {
        NotificationCommand command = json.readValue(message.getBody(), NotificationCommand.class);
        if (!recentlyHandled.add(command.commandId())) {
            log.info("Duplicate notification {} skipped", command.commandId());
            return;
        }
        try {
            TemplateRenderer.RenderedEmail rendered = templates.render(command.template(), command.variables());
            email.send(command.recipient(), rendered.subject(), rendered.body());
            if ("order-confirmed".equals(command.template())) {
                archiver.archiveReceipt(command);
            }
            log.info("Sent '{}' to {}", command.template(), mask(command.recipient()));
        } catch (RuntimeException e) {
            recentlyHandled.remove(command.commandId());     // allow the retry to process it again
            throw e;
        }
    }

    /** Never log full email addresses: personal data (GDPR) and OWASP logging guidance. */
    static String mask(String emailAddress) {
        int at = emailAddress.indexOf('@');
        return at <= 1 ? "***" : emailAddress.charAt(0) + "***" + emailAddress.substring(at);
    }
}
