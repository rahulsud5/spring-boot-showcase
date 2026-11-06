package io.showcase.contracts.messaging;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A COMMAND ("send this email"), not an event ("order confirmed").
 *
 * <p>Commands have exactly one intended handler and imperative names; events have any number of
 * listeners and past-tense names. That difference is one of the reasons the command goes over a
 * RabbitMQ queue (work distribution, ack, retry, DLQ) while events go over Kafka (fan-out,
 * replay).
 *
 * @param commandId  idempotency key: the consumer ignores duplicates (at-least-once delivery)
 * @param template   which email template to render, e.g. "order-confirmed"
 * @param variables  template variables (order id, total...)
 */
public record NotificationCommand(UUID commandId, String recipient, String template, Map<String, String> variables) {

    public NotificationCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(template, "template");
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
