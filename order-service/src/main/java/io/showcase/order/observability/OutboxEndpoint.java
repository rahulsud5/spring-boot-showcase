package io.showcase.order.observability;

import io.showcase.order.messaging.outbox.OutboxRelay;
import io.showcase.order.messaging.outbox.OutboxRepository;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * CUSTOM ACTUATOR ENDPOINT at /actuator/outbox: an operational tool built into the application.
 *
 * <ul>
 *   <li>{@code @ReadOperation}  maps to GET  /actuator/outbox: backlog statistics</li>
 *   <li>{@code @WriteOperation} maps to POST /actuator/outbox: trigger an immediate relay run</li>
 * </ul>
 * Endpoints are technology-agnostic: the same class is exposed over HTTP and JMX. It must be listed in
 * {@code management.endpoints.web.exposure.include}, and it's protected by the OPS role
 * (SecurityConfig). Actuator endpoints are powerful, so never expose them unauthenticated.
 */
@Component
@Endpoint(id = "outbox")
class OutboxEndpoint {

    private final OutboxRepository repository;
    private final OutboxRelay relay;

    OutboxEndpoint(OutboxRepository repository, OutboxRelay relay) {
        this.repository = repository;
        this.relay = relay;
    }

    @ReadOperation
    Map<String, Object> status() {
        return Map.of(
                "unpublished", repository.countByPublishedAtIsNull(),
                "oldestUnpublished", repository.oldestUnpublishedCreatedAt().map(Object::toString).orElse("none"));
    }

    @WriteOperation
    Map<String, Object> flush() {
        relay.publishPending();
        return status();
    }
}
