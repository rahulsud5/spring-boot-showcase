package io.showcase.order.integration.catalog;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * DECLARATIVE HTTP CLIENT (an HTTP Service Interface): you write the interface, Spring implements it.
 *
 * <p>It's the built-in replacement for OpenFeign. Spring Framework 7 adds the HTTP SERVICE REGISTRY:
 * {@code @ImportHttpServices} (HttpClientsConfig) registers the client as a bean, and Spring Boot 4
 * configures it from properties per GROUP ({@code spring.http.serviceclient.catalog.base-url},
 * timeouts...). There's no factory boilerplate. It's backed by {@code RestClient} (synchronous, ideal
 * on virtual threads); a {@code WebClient}-backed reactive variant also exists.
 *
 * <p>The same inventory-service is called over REST here (the catalog/prices is a public, cacheable,
 * resource-oriented API) and over gRPC in GrpcInventoryAdapter (internal, high-volume RPC). See
 * docs/08-rest-grpc-graphql.md for the comparison.
 */
@HttpExchange(url = "/api/products", accept = "application/json")
public interface CatalogClient {

    @GetExchange("/{sku}/price")
    PriceResponse price(@PathVariable String sku);

    record PriceResponse(String sku, BigDecimal amount, String currency) {}
}
