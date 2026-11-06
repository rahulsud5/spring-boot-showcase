package io.showcase.order.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * TYPE-SAFE CONFIGURATION: everything under {@code app.*} in application.yml binds onto this
 * immutable record tree.
 *
 * <p>Why it's better than scattered {@code @Value("${...}")} annotations:
 * <ul>
 *   <li>One discoverable place for all custom settings, with IDE autocompletion (the
 *       configuration-processor generates the metadata).</li>
 *   <li>{@code @Validated} + Bean Validation: a wrong config FAILS STARTUP with a clear message
 *       instead of failing at 3 a.m. on the first request ("fail fast").</li>
 *   <li>RELAXED BINDING: {@code app.inventory.grpc-target}, {@code APP_INVENTORY_GRPCTARGET} or
 *       {@code APP_INVENTORY_GRPC_TARGET} all bind to {@code grpcTarget}, so the same class works for
 *       YAML, env vars (containers) and system properties.</li>
 *   <li>Rich types: {@code Duration} accepts "800ms", "5s", "7d".</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid Inventory inventory, @Valid Outbox outbox, @Valid Security security, @Valid Fraud fraud) {

    /** gRPC target, e.g. "static://localhost:9090" or "dns:///inventory-service:9090" (client-side load balancing). */
    public record Inventory(@NotBlank String grpcTarget, @DefaultValue("800ms") Duration grpcDeadline) {}

    public record Outbox(
            @DefaultValue("100") @Min(1) int batchSize,
            @DefaultValue("1000") int healthBacklogThreshold,
            @DefaultValue("7d") Duration retention) {}

    /** SHA-256 hashes of partner API keys. Store HASHES, never the keys themselves (like passwords). */
    public record Security(@DefaultValue List<String> partnerApiKeySha256) {}

    /** Selects which FraudScreening implementation FraudScreeningRegistrar registers. */
    public record Fraud(@DefaultValue("lenient") String mode, @DefaultValue("5000") long reviewThresholdAmount) {}
}
