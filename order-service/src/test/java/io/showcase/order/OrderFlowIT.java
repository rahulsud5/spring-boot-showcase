package io.showcase.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.showcase.contracts.events.EventEnvelope;
import io.showcase.contracts.events.InventoryEvent;
import io.showcase.contracts.messaging.Messaging.Topics;
import io.showcase.order.application.port.InventoryPort;
import io.showcase.order.application.port.PricingPort;
import io.showcase.order.domain.Money;
import io.showcase.order.domain.OrderStatus;
import io.showcase.order.persistence.OrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/**
 * END-TO-END INTEGRATION TEST of the saga, in-process: the full Spring context + real Postgres,
 * Kafka, RabbitMQ and Redis (Testcontainers).
 *
 * <pre>
 *  POST /api/orders ─► DB (order PENDING + outbox row) ─► OutboxRelay ─► Kafka orders.events.v1
 *  test publishes StockReserved on inventory.events.v1 ─► listener ─► order CONFIRMED
 * </pre>
 *
 * Remote collaborators (inventory gRPC, catalog REST) are replaced with {@code @MockitoBean} PORTS.
 * That's the payoff of the hexagonal design: no gRPC server needed. Asynchronous outcomes are
 * asserted with Awaitility (polling with a timeout), NEVER with {@code Thread.sleep}.
 */
@SpringBootTest(properties = "app.outbox.poll-interval=PT0.1S")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderFlowIT {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    OrderRepository orders;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JsonMapper json;

    @MockitoBean
    PricingPort pricing;
    @MockitoBean
    InventoryPort inventory;

    @Test
    void placedOrderIsConfirmedWhenInventoryReservesStock() {
        given(pricing.priceOf(any())).willReturn(Money.of("12.50", "EUR"));
        given(inventory.checkAvailability(anyList())).willReturn(new InventoryPort.Availability.Available());

        var result = mvc.post().uri("/api/orders")
                .with(jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_orders.write")))
                .header("Idempotency-Key", "it-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"shippingAddress": {"street": "1 Main St", "city": "Springfield", "postalCode": "1000", "country": "NL"},
                         "items": [{"sku": "SKU-0001", "quantity": 2}]}
                        """)
                .exchange();
        assertThat(result).hasStatus(201);
        UUID orderId = UUID.fromString(json.readTree(result.getResponse().getContentAsByteArray()).get("id").asString());

        // inventory-service would react to OrderPlaced; we simulate its reply
        var reply = EventEnvelope.wrap(new InventoryEvent.StockReserved(orderId, "res-42"), Instant.now());
        kafka.send(Topics.INVENTORY_EVENTS, orderId.toString(), json.writeValueAsString(reply));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(orders.findById(orderId)).get()
                        .extracting(o -> o.getStatus())
                        .isEqualTo(OrderStatus.CONFIRMED));
    }
}
