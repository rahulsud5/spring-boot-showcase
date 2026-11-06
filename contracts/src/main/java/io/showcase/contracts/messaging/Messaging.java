package io.showcase.contracts.messaging;

/**
 * Broker resource names in ONE place. Typos in topic names are a classic production incident
 * ("why is nobody consuming?").
 *
 * <p><b>Naming convention:</b> {@code <domain>.<kind>.<version>}. The version suffix allows a
 * breaking schema change to go to a new topic while old consumers keep reading v1.
 */
public final class Messaging {

    private Messaging() {
    }

    /** Kafka: event STREAMS. Durable, replayable logs, partitioned by orderId. */
    public static final class Topics {
        public static final String ORDER_EVENTS = "orders.events.v1";
        public static final String INVENTORY_EVENTS = "inventory.events.v1";
        public static final String DLT_SUFFIX = "-dlt";

        private Topics() {
        }
    }

    /**
     * RabbitMQ: COMMAND / task queues. Routed by key, per-message ack, dead-lettering.
     * See docs/07-messaging-kafka-vs-rabbitmq.md for why notifications go through RabbitMQ while
     * domain events go through Kafka.
     */
    public static final class Rabbit {
        public static final String NOTIFICATIONS_EXCHANGE = "notifications";          // topic exchange
        public static final String NOTIFICATIONS_DLX = "notifications.dlx";           // dead-letter exchange
        public static final String EMAIL_QUEUE = "notifications.email";
        public static final String EMAIL_DLQ = "notifications.email.dlq";
        /** Routing keys look like notification.email.order-confirmed; the queue binds "notification.email.#". */
        public static final String EMAIL_ROUTING_PATTERN = "notification.email.#";

        public static String emailRoutingKey(String template) {
            return "notification.email." + template;
        }

        private Rabbit() {
        }
    }
}
