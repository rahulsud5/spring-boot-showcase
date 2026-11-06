package io.showcase.order.application.port;

import io.showcase.contracts.events.IntegrationEvent;

/**
 * Outbound port for publishing integration events.
 *
 * <p>The application layer states WHAT it needs ("publish this event as part of my transaction") and
 * the messaging layer decides HOW: today a transactional outbox (OutboxWriter), tomorrow perhaps CDC or
 * a different broker. This port also breaks the package cycle application ⇄ messaging that ArchUnit
 * flagged (see ArchitectureTest.noPackageCycles).
 *
 * <p>Contract: must be called INSIDE the business transaction. The implementation enforces this
 * with {@code Propagation.MANDATORY}.
 */
public interface IntegrationEventPublisher {

    void publish(String topic, IntegrationEvent event);
}
