package io.showcase.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * A GLOBAL FILTER: applies to EVERY route.
 *
 * <ul>
 *   <li>"pre" logic (before {@code chain.filter}): ensure an X-Correlation-Id header exists and pass it
 *       downstream.</li>
 *   <li>"post" logic ({@code .then(...)}, after the downstream response): log the status and latency.</li>
 * </ul>
 * REACTIVE rule: never block in a filter (no JDBC, no Thread.sleep). Blocking an event-loop thread
 * stalls every request multiplexed on it.
 */
@Component
class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Correlation-Id";
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate().header(HEADER, correlationId).build();
        exchange.getResponse().getHeaders().set(HEADER, correlationId);
        long start = System.nanoTime();

        return chain.filter(exchange.mutate().request(request).build())
                .then(Mono.fromRunnable(() -> log.info("{} {} -> {} in {} ms [{}]",
                        request.getMethod(), request.getPath(), exchange.getResponse().getStatusCode(),
                        (System.nanoTime() - start) / 1_000_000, correlationId)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;     // run first, so even rejected requests get an id
    }
}
