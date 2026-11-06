package io.showcase.notification.config;

import io.showcase.contracts.messaging.Messaging.Rabbit;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RABBITMQ TOPOLOGY, owned by the CONSUMER.
 *
 * <pre>
 *  order-service ──publish──► [notifications] topic exchange
 *                                   │ binding "notification.email.#"
 *                                   ▼
 *                         ┌─[notifications.email]  quorum queue ──► NotificationListener
 *                         │       │ reject after 3 attempts (x-dead-letter-exchange)
 *                         │       ▼
 *                         └─[notifications.dlx] ──► [notifications.email.dlq] ──► DeadLetterAlerts (SNS)
 * </pre>
 *
 * <ul>
 *   <li><b>Topic exchange</b>: routing by key pattern. A future "notification.sms.#" queue could bind
 *       to the SAME exchange without the publisher changing anything.</li>
 *   <li><b>Quorum queue</b> ({@code x-queue-type: quorum}): replicated with Raft across nodes. It's the
 *       modern default for durable data; classic mirrored queues were removed in RabbitMQ 4.</li>
 *   <li><b>Dead-letter exchange</b>: rejected or expired messages are re-routed, not lost, for
 *       inspection and replay. The poison-message pattern.</li>
 *   <li><b>delivery-limit</b>: the quorum queue itself dead-letters a message after N redeliveries,
 *       a broker-side safety net on top of the listener's retry policy.</li>
 * </ul>
 * A {@link Declarables} bean declares several objects at once; Boot's RabbitAdmin applies them on
 * connection.
 */
@Configuration(proxyBeanMethods = false)
class RabbitTopologyConfig {

    @Bean
    Declarables notificationTopology() {
        TopicExchange exchange = new TopicExchange(Rabbit.NOTIFICATIONS_EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(Rabbit.NOTIFICATIONS_DLX, true, false);

        Queue email = QueueBuilder.durable(Rabbit.EMAIL_QUEUE)
                .quorum()
                .deliveryLimit(5)
                .deadLetterExchange(Rabbit.NOTIFICATIONS_DLX)
                .deadLetterRoutingKey(Rabbit.EMAIL_DLQ)
                .build();
        Queue emailDlq = QueueBuilder.durable(Rabbit.EMAIL_DLQ).quorum().build();

        Binding emailBinding = BindingBuilder.bind(email).to(exchange).with(Rabbit.EMAIL_ROUTING_PATTERN);
        Binding dlqBinding = BindingBuilder.bind(emailDlq).to(dlx).with(Rabbit.EMAIL_DLQ);

        return new Declarables(exchange, dlx, email, emailDlq, emailBinding, dlqBinding);
    }
}
