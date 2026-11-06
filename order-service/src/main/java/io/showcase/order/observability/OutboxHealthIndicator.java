package io.showcase.order.observability;

import io.showcase.order.config.AppProperties;
import io.showcase.order.messaging.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * CUSTOM HEALTH INDICATOR: it appears under /actuator/health as "outbox" (the bean name minus the
 * "HealthIndicator" suffix).
 *
 * <p>A growing outbox backlog means Kafka is unreachable or the relay is stuck: the service still
 * takes orders, but downstream services stop hearing about them. We report a custom DEGRADED status
 * (mapped to HTTP 200 in application.yml) instead of DOWN. DOWN on readiness would pull every instance
 * out of the load balancer, turning a partial outage into a total one.
 *
 * <p>Note: Spring Boot 4 moved health contributors to the {@code spring-boot-health} module
 * ({@code org.springframework.boot.health.contributor}).
 */
@Component
class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxRepository outbox;
    private final AppProperties properties;
    private final Clock clock;

    OutboxHealthIndicator(OutboxRepository outbox, AppProperties properties, Clock clock) {
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Health health() {
        long backlog = outbox.countByPublishedAtIsNull();
        Duration oldestAge = outbox.oldestUnpublishedCreatedAt()
                .map(oldest -> Duration.between(oldest, clock.instant()))
                .orElse(Duration.ZERO);

        Health.Builder builder = backlog > properties.outbox().healthBacklogThreshold()
                ? Health.status("DEGRADED")
                : Health.up();
        return builder
                .withDetail("backlog", backlog)
                .withDetail("oldestUnpublishedAgeSeconds", oldestAge.toSeconds())
                .build();
    }
}
