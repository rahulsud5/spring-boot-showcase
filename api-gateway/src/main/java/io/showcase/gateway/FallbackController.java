package io.showcase.gateway;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Circuit-breaker FALLBACK target ({@code fallbackUri: forward:/fallback/orders}). When order-service is
 * failing or the circuit is OPEN, clients get a fast, well-formed 503 + Retry-After instead of a hanging
 * connection or an HTML error page.
 */
@RestController
class FallbackController {

    @RequestMapping("/fallback/orders")
    Mono<ProblemDetail> ordersUnavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The order service is temporarily unavailable. Please retry shortly.");
        problem.setType(URI.create("https://api.showcase.example/problems/service-unavailable"));
        problem.setProperty("retryAfterSeconds", 10);
        return Mono.just(problem);
    }
}
