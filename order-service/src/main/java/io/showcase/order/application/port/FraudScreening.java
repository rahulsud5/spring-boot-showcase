package io.showcase.order.application.port;

import io.showcase.order.domain.Money;

/**
 * Fraud check strategy. Which implementation exists in the context is decided at startup by
 * FraudScreeningRegistrar, using Spring Framework 7's programmatic {@code BeanRegistrar} API.
 */
public interface FraudScreening {

    Verdict screen(String customerId, Money total);

    enum Verdict { ACCEPT, REVIEW, REJECT }
}
