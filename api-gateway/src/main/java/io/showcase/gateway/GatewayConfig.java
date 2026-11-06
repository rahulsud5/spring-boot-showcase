package io.showcase.gateway;

import java.security.Principal;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

/**
 * Gateway beans.
 *
 * <p>Routes can be defined in YAML (application.yml: the main routes) or with the JAVA DSL (below). The
 * DSL suits routes needing code: custom predicates, computed URIs, or filters with lambdas.
 */
@Configuration(proxyBeanMethods = false)
class GatewayConfig {

    /**
     * RATE-LIMIT KEY: whom do we count requests against? The authenticated user (JWT subject) when
     * present, otherwise the client IP. Limiting by IP only would punish everyone behind one corporate NAT;
     * limiting by user is fairer and harder to evade.
     */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.fromSupplier(() -> exchange.getRequest().getRemoteAddress() == null
                        ? "anonymous"
                        : Objects.requireNonNull(exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress()));
    }

    /**
     * JAVA DSL route: keep a LEGACY public URL working while the backend has moved on.
     * {@code GET /legacy/catalog/SKU-1} is rewritten to {@code /api/products/SKU-1} on inventory-service.
     * That's the strangler-fig pattern for migrating APIs without breaking clients.
     */
    @Bean
    RouteLocator legacyRoutes(RouteLocatorBuilder routes, @Value("${INVENTORY_SERVICE_URI:http://localhost:8082}") String inventoryUri) {
        return routes.routes()
                .route("legacy-catalog", r -> r
                        .path("/legacy/catalog/**")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f
                                .rewritePath("/legacy/catalog/(?<sku>.*)", "/api/products/${sku}")
                                .addResponseHeader("Deprecation", "true")                    // RFC 9745: tell clients to migrate
                                .addResponseHeader("Link", "</api/products>; rel=\"successor-version\""))
                        .uri(inventoryUri))
                .build();
    }
}
