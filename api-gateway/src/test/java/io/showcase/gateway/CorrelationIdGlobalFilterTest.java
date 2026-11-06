package io.showcase.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit test of a reactive gateway filter with Spring's mock exchange and Reactor's {@link StepVerifier}
 * (which subscribes and asserts the reactive sequence completes).
 */
class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void generatesAnIdWhenMissingAndPropagatesItDownstream() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders/mine"));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = ex -> { downstream.set(ex); return Mono.empty(); };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String id = downstream.get().getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER);
        assertThat(id).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER)).isEqualTo(id);
    }

    @Test
    void replacesUnsafeIncomingIds() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header(CorrelationIdGlobalFilter.HEADER, "bad\r\ninjected-log-line"));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, ex -> { downstream.set(ex); return Mono.empty(); })).verifyComplete();

        assertThat(downstream.get().getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .doesNotContain("\n").hasSize(36);
    }
}
