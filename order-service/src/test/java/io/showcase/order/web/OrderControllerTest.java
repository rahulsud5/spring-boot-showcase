package io.showcase.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.showcase.order.application.OrderApplicationService;
import io.showcase.order.application.OrderQueryService;
import io.showcase.order.application.OrderViews.OrderDetails;
import io.showcase.order.domain.OrderStatus;
import io.showcase.order.persistence.OrderReportingRepository.StatusHistoryEntry;
import io.showcase.order.config.AppProperties;
import io.showcase.order.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * WEB SLICE TEST: {@code @WebMvcTest} starts ONLY the MVC layer (controllers, advice, filters,
 * converters, security). There's no database, Kafka or Redis: collaborators are {@code @MockitoBean}s
 * (Spring Framework 6.2+, the replacement for Boot's deprecated {@code @MockBean}).
 *
 * <p>It verifies what unit tests can't: URL mappings, JSON (de)serialization, Bean Validation, Problem
 * Details, API versioning headers, and SECURITY rules (401 vs 403 vs 2xx).
 *
 * <p>{@link MockMvcTester} (Spring 6.2) is MockMvc with fluent AssertJ assertions. The {@code jwt()}
 * post-processor (spring-security-test) injects an authenticated JWT with chosen claims and
 * authorities, with no real token or auth server.
 *
 * <p>Boot 4 note: the slice moved to {@code org.springframework.boot.webmvc.test.autoconfigure} and
 * comes with spring-boot-starter-webmvc-test.
 */
@WebMvcTest({OrderController.class, PartnerWebhookController.class})
@Import(SecurityConfig.class)
// Slices ignore @ConfigurationPropertiesScan from the main class, so register the properties SecurityConfig needs
@EnableConfigurationProperties(AppProperties.class)
class OrderControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    OrderApplicationService orders;
    @MockitoBean
    OrderQueryService queries;
    @MockitoBean
    IdempotencyService idempotency;

    private static final String VALID_BODY = """
            {
              "shippingAddress": {"street": "1 Main St", "city": "Springfield", "postalCode": "1000", "country": "NL"},
              "items": [{"sku": "SKU-0001", "quantity": 2}],
              "discountCode": "WELCOME10"
            }
            """;

    private static OrderDetails sampleOrder(UUID id) {
        return new OrderDetails(id, "alice", OrderStatus.PENDING, new BigDecimal("18.00"), new BigDecimal("2.00"),
                "EUR", "Springfield", "NL", null, 0, Instant.parse("2026-11-06T10:00:00Z"), List.of());
    }

    @Test
    void rejectsAnonymousRequestsWith401() {
        assertThat(mvc.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .hasStatus(401);
    }

    @Test
    void rejectsTokensWithoutTheWriteScopeWith403() {
        assertThat(mvc.post().uri("/api/orders")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.read")))
                .header("Idempotency-Key", "key-12345678")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .hasStatus(403);
    }

    @Test
    void createsAnOrderAndReturns201WithLocation() {
        UUID id = UUID.randomUUID();
        var started = new IdempotencyService.Outcome.Started("idem:key");
        given(idempotency.begin("alice", "key-12345678")).willReturn(started);
        given(orders.placeOrder(any())).willReturn(sampleOrder(id));

        MvcTestResult result = mvc.post().uri("/api/orders")
                .with(jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_orders.write")))
                .header("Idempotency-Key", "key-12345678")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).headers().hasValue("Location", "http://localhost/api/orders/" + id);
        assertThat(result).bodyJson().extractingPath("$.total").isEqualTo(18.00);
        verify(idempotency).complete(started, id);
    }

    @Test
    void replaysACompletedIdempotentRequestWithoutCreatingAnotherOrder() {
        UUID id = UUID.randomUUID();
        given(idempotency.begin("alice", "key-12345678")).willReturn(new IdempotencyService.Outcome.Completed(id));
        given(queries.get(id)).willReturn(sampleOrder(id));

        assertThat(mvc.post().uri("/api/orders")
                .with(jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_orders.write")))
                .header("Idempotency-Key", "key-12345678")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .hasStatus(200);
        verify(orders, never()).placeOrder(any());
    }

    @Test
    void invalidInputProducesProblemDetails() {
        String badSku = VALID_BODY.replace("SKU-0001", "not a sku");

        MvcTestResult result = mvc.post().uri("/api/orders")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.write")))
                .header("Idempotency-Key", "key-12345678")
                .contentType(MediaType.APPLICATION_JSON).content(badSku)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void apiVersion2AddsTheStatusHistory() {
        UUID id = UUID.randomUUID();
        given(queries.get(id)).willReturn(sampleOrder(id));
        given(queries.history(id)).willReturn(List.of(new StatusHistoryEntry("", "PENDING", Instant.parse("2026-11-06T10:00:00Z"))));

        MvcTestResult v2 = mvc.get().uri("/api/orders/{id}", id)
                .header("X-API-Version", "2")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.read")))
                .exchange();

        assertThat(v2).hasStatusOk();
        assertThat(v2).bodyJson().extractingPath("$.history[0].toStatus").isEqualTo("PENDING");

        MvcTestResult v1 = mvc.get().uri("/api/orders/{id}", id)
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.read")))
                .exchange();
        assertThat(v1).bodyJson().extractingPath("$.status").isEqualTo("PENDING");    // default version 1: flat shape
    }

    @Test
    void webhookAcceptsAValidPartnerApiKeyOnly() {
        String body = """
                {"providerEventId": "evt_1", "orderId": "%s", "status": "PAID"}
                """.formatted(UUID.randomUUID());

        assertThat(mvc.post().uri("/api/webhooks/payments").header("X-API-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(401);

        // sha256("local-dev-partner-key") is configured in application.yml
        assertThat(mvc.post().uri("/api/webhooks/payments").header("X-API-Key", "local-dev-partner-key")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(202);
    }

    @Test
    void cancelUsesTheCallerAsActor() {
        UUID id = UUID.randomUUID();
        given(queries.get(id)).willReturn(sampleOrder(id));
        given(orders.cancel(eq(id), any(), eq("alice"))).willReturn(sampleOrder(id));

        assertThat(mvc.post().uri("/api/orders/{id}/cancel", id)
                .with(jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_orders.write")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"duplicate\"}"))
                .hasStatusOk();
        verify(orders).cancel(id, "duplicate", "alice");
    }
}
