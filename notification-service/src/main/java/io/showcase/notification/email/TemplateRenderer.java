package io.showcase.notification.email;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Minimal template rendering with Java text blocks + {@code String.formatted}. A real system would
 * use Thymeleaf or Mustache (both have Spring Boot starters) with HTML templates managed by a content
 * team. Unknown templates FAIL LOUDLY, so the message is dead-lettered instead of sending a blank email.
 */
@Component
public class TemplateRenderer {

    public record RenderedEmail(String subject, String body) {}

    public RenderedEmail render(String template, Map<String, String> vars) {
        String orderId = vars.getOrDefault("orderId", "?");
        return switch (template) {
            case "order-confirmed" -> new RenderedEmail("Your order is confirmed", """
                    Good news! Order %s is confirmed.
                    Total: %s
                    We'll let you know when it ships.
                    """.formatted(orderId, vars.getOrDefault("total", "?")));
            case "order-cancelled" -> new RenderedEmail("Your order was cancelled", """
                    Order %s has been cancelled. Any payment will be refunded within 5 business days.
                    """.formatted(orderId));
            case "order-shipped" -> new RenderedEmail("Your order is on its way", """
                    Order %s has shipped.
                    """.formatted(orderId));
            default -> throw new IllegalArgumentException("Unknown email template: " + template);
        };
    }
}
