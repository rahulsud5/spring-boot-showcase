package io.showcase.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * RESILIENCE configuration: two complementary toolkits.
 *
 * <h2>1. Spring Framework 7 built-ins: {@code @EnableResilientMethods}</h2>
 * Activates {@code @Retryable} (retry with back-off and jitter) and {@code @ConcurrencyLimit} (a
 * bulkhead or throttle) on any Spring bean, with no extra library. That covers the most common needs.
 *
 * <h2>2. Resilience4j circuit breakers via Spring Cloud CircuitBreaker</h2>
 * For STATEFUL protection (circuit breaking based on failure and slow-call RATES, combined with a
 * time limiter), we use Resilience4j behind the vendor-neutral Spring Cloud CircuitBreaker API
 * ({@code CircuitBreakerFactory.create("inventory").run(supplier, fallback)}).
 *
 * <p>Tuning the breaker (all values are deliberate):
 * <ul>
 *   <li>sliding window of the last 20 calls, evaluated once at least 10 calls were made (no tripping
 *       on 1 failure at startup)</li>
 *   <li>OPEN when 50% of calls FAIL, or when 50% are SLOW (> 500 ms). Slow calls are often the earliest
 *       sign of trouble</li>
 *   <li>stay OPEN for 10 s, then HALF_OPEN with 3 trial calls</li>
 *   <li>time limiter at 1 s: never wait longer than that for the dependency</li>
 * </ul>
 * Breaker state is exported as metrics (resilience4j.circuitbreaker.state) and appears in
 * /actuator/health when configured.
 */
@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
class ResilienceConfig {

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(10)
                        .failureRateThreshold(50)
                        .slowCallDurationThreshold(Duration.ofMillis(500))
                        .slowCallRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(1))
                        .build())
                .build());
    }
}
