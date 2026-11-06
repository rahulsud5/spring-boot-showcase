package io.showcase.order.integration.catalog;

import io.showcase.order.application.port.PricingPort;
import io.showcase.order.domain.Money;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Pricing adapter: three cross-cutting concerns stacked as ANNOTATIONS. Each one is a proxy layer
 * (AOP), with zero code inside the method.
 *
 * <ul>
 *   <li>{@code @Cacheable("prices")}: prices change rarely, so a Redis hit skips the HTTP call entirely
 *       (TTL 30 s, see CacheConfig).</li>
 *   <li>{@code @Retryable} (Spring Framework 7 core): retries TRANSIENT failures (connection errors,
 *       5xx) up to 3 times with exponential back-off + jitter. It never retries 4xx: a "404 unknown
 *       SKU" won't fix itself, and retrying non-idempotent calls is dangerous. A GET is idempotent.</li>
 *   <li>{@code @ConcurrencyLimit(20)} (Spring Framework 7 core): at most 20 concurrent calls; more
 *       callers wait. It's a BULKHEAD that keeps a slow catalog from exhausting our resources. That
 *       matters with virtual threads, where thread count no longer limits concurrency for us.</li>
 * </ul>
 * Annotations activated by {@code @EnableResilientMethods} (ResilienceConfig) and {@code @EnableCaching}.
 */
@Component
class CatalogPricingAdapter implements PricingPort {

    private final CatalogClient catalog;

    CatalogPricingAdapter(CatalogClient catalog) {
        this.catalog = catalog;
    }

    @Override
    @Cacheable(cacheNames = "prices", key = "#sku")
    @Retryable(includes = {ResourceAccessException.class, HttpServerErrorException.class},
               maxRetries = 3, delay = 100, multiplier = 2, jitter = 50, maxDelay = 1_000)
    @ConcurrencyLimit(20)
    public Money priceOf(String sku) {
        CatalogClient.PriceResponse price = catalog.price(sku);
        return new Money(price.amount(), price.currency());
    }
}
