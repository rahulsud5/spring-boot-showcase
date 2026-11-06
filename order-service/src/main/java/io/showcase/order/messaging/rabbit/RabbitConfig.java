package io.showcase.order.messaging.rabbit;

import io.showcase.contracts.messaging.Messaging.Rabbit;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The PUBLISHER side declares only the EXCHANGE it publishes to. Queues and bindings belong to the
 * CONSUMER (notification-service), so consumers can add queues without touching the publisher.
 * That's the decoupling AMQP's exchange/queue split was designed for.
 *
 * <p>Boot's auto-configured {@code RabbitAdmin} declares every {@code Exchange/Queue/Binding} bean on
 * the broker when the first connection opens. Declarations are idempotent, but they must match:
 * re-declaring with different arguments fails with PRECONDITION_FAILED.
 */
@Configuration(proxyBeanMethods = false)
class RabbitConfig {

    @Bean
    TopicExchange notificationsExchange() {
        // TOPIC exchange: routes by pattern matching on the routing key ("notification.email.#")
        return ExchangeBuilder.topicExchange(Rabbit.NOTIFICATIONS_EXCHANGE).durable(true).build();
    }
}
