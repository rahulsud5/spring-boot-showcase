package io.showcase.order.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine-to-machine endpoint for a payment provider. It's authenticated by an API KEY (see
 * ApiKeyAuthenticationFilter) instead of a JWT, because many third parties only support static
 * credentials or HMAC signatures. Two authentication mechanisms coexist in ONE filter chain.
 *
 * <p>Webhooks are ALWAYS at-least-once: providers retry until they get a 2xx. Respond quickly (202) and
 * process asynchronously and idempotently by event id.
 */
@RestController
@RequestMapping("/api/webhooks")
class PartnerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PartnerWebhookController.class);

    record PaymentEvent(@NotBlank String providerEventId, UUID orderId, @NotBlank String status) {}

    @PostMapping("/payments")
    ResponseEntity<Map<String, String>> paymentEvent(@Valid @RequestBody PaymentEvent event, Authentication partner) {
        log.info("Payment event {} for order {} from partner {}", event.providerEventId(), event.orderId(), partner.getName());
        return ResponseEntity.accepted().body(Map.of("received", event.providerEventId()));
    }
}
