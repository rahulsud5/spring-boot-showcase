package io.showcase.order.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * BUSINESS METRICS with Micrometer: the "SLF4J of metrics". The same code exports to Prometheus,
 * OTLP, Datadog, CloudWatch... depending on which registry is on the classpath.
 *
 * <p>The four core meter types:
 * <ul>
 *   <li><b>Counter</b>: only goes up (orders placed). Dashboards plot its RATE.</li>
 *   <li><b>Gauge</b>: a current value sampled on scrape (the outbox backlog; see observability.OutboxMetrics).</li>
 *   <li><b>Timer</b>: count + total time + max (+ histogram); from {@code @Observed} on placeOrder.</li>
 *   <li><b>DistributionSummary</b>: like a timer but for non-time values (order value), with
 *       percentiles.</li>
 * </ul>
 *
 * <p><b>Cardinality warning:</b> every unique combination of tag values is a separate time series.
 * Tag with BOUNDED values (currency, status), never with order ids, emails or URLs containing ids.
 * That's the most common way to take down a metrics backend.
 */
@Component
public class OrderMetrics {

    private final MeterRegistry registry;
    private final DistributionSummary orderValue;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.orderValue = DistributionSummary.builder("orders.value")
                .description("Order total amount")
                .baseUnit("currency_units")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()         // enables server-side quantiles (histogram_quantile in PromQL)
                .register(registry);
    }

    public void orderPlaced(String currency, BigDecimal total) {
        Counter.builder("orders.placed").tag("currency", currency).register(registry).increment();
        orderValue.record(total.doubleValue());
    }

    public void degradedPlacement(String reason) {
        Counter.builder("orders.placed.degraded")
                .description("Orders accepted while the inventory pre-check was unavailable")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
