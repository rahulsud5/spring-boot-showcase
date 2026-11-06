package io.showcase.inventory.catalog;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public, read-only CATALOG REST API.
 *
 * <p>It's REST (not gRPC) because it's resource-oriented, public, and HTTP-CACHEABLE.
 * {@code Cache-Control: max-age=30} lets browsers, CDNs and the gateway's local response cache serve
 * repeated price lookups without hitting the service at all. order-service calls
 * {@code /{sku}/price} through its declarative HTTP interface client (CatalogClient).
 */
@RestController
@RequestMapping("/api/products")
class CatalogController {

    private final ProductRepository products;
    private final CatalogAnalytics analytics;

    CatalogController(ProductRepository products, CatalogAnalytics analytics) {
        this.products = products;
        this.analytics = analytics;
    }

    record PriceResponse(String sku, BigDecimal amount, String currency) {}

    @GetMapping("/{sku}")
    ResponseEntity<Product> product(@PathVariable String sku) {
        return products.findById(sku)
                .map(p -> ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30))).body(p))
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Unknown SKU " + sku));
    }

    @GetMapping("/{sku}/price")
    ResponseEntity<PriceResponse> price(@PathVariable String sku) {
        Product p = products.findById(sku)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Unknown SKU " + sku));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)))
                .body(new PriceResponse(sku, p.getPrice().amount(), p.getPrice().currency()));
    }

    /** Full-text search (Mongo text index), relevance-ordered. */
    @GetMapping("/search")
    List<Product> search(@RequestParam String q, @RequestParam(defaultValue = "20") int size) {
        return products.findAllBy(TextCriteria.forDefaultLanguage().matching(q), PageRequest.of(0, Math.min(size, 100)));
    }

    @GetMapping("/category/{category}")
    List<Product> byCategory(@PathVariable String category, @RequestParam(defaultValue = "1000000") BigDecimal maxPrice) {
        return products.findByCategoryAndPriceAmountLessThanEqualOrderByPriceAmountAsc(category, maxPrice);
    }

    @GetMapping("/analytics/categories")
    List<CatalogAnalytics.CategoryStats> categoryStats() {
        return analytics.categoryStats();
    }
}
