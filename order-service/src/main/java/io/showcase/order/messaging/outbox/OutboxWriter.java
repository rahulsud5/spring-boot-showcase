package io.showcase.order.messaging.outbox;

import io.showcase.contracts.events.EventEnvelope;
import io.showcase.contracts.events.IntegrationEvent;
import io.showcase.order.application.port.IntegrationEventPublisher;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends integration events to the outbox: the implementation of the {@link IntegrationEventPublisher} port.
 *
 * <p>{@code Propagation.MANDATORY}: it THROWS if no transaction is active. Writing to the outbox
 * outside the business transaction would defeat the whole pattern, so this turns a subtle
 * consistency bug into an immediate, obvious exception. Propagation as a design-by-contract tool.
 *
 * <p>Uses {@link JsonMapper}, Jackson 3's immutable, thread-safe mapper that Spring Boot 4
 * auto-configures. Jackson 3 lives in the {@code tools.jackson} package, throws UNCHECKED
 * {@code JacksonException}, and handles java.time types out of the box.
 */
@Component
public class OutboxWriter implements IntegrationEventPublisher {

    private final OutboxRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, IntegrationEvent event) {
        EventEnvelope<IntegrationEvent> envelope = EventEnvelope.wrap(event, clock.instant());
        repository.save(new OutboxEvent(
                envelope.eventId(),
                "Order",
                envelope.aggregateId(),
                envelope.eventType(),
                topic,
                json.writeValueAsString(envelope),
                envelope.occurredAt()));
    }
}
