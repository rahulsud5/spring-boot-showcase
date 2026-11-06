package io.showcase.order.application.port;

import io.showcase.order.domain.Money;

/**
 * Outbound port for prices. Implemented by CatalogPricingAdapter (REST via a Spring 7 HTTP
 * interface client). Prices are ALWAYS resolved server-side: never trust a price sent by the client
 * (OWASP: business-logic tampering).
 */
public interface PricingPort {

    Money priceOf(String sku);
}
