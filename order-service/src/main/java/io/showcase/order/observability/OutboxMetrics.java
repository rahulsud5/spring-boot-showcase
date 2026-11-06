package io.showcase.order.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.showcase.order.messaging.outbox.OutboxRepository;
import org.springframework.stereotype.Component;

/**
 * A {@link MeterBinder} bean: Spring Boot binds it to every MeterRegistry at startup.
 *
 * <p>A GAUGE samples a FUNCTION on each scrape. Here that's a COUNT served by the partial index
 * ix_outbox_unpublished, which is cheap even on big tables. Alert on it: a growing backlog means
 * events aren't reaching Kafka.
 */
@Component
class OutboxMetrics implements MeterBinder {

    private final OutboxRepository outbox;

    OutboxMetrics(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.backlog", outbox, OutboxRepository::countByPublishedAtIsNull)
                .description("Integration events not yet published to Kafka")
                .register(registry);
    }
}
