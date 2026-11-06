package io.showcase.order.application.fraud;

import io.showcase.order.application.port.FraudScreening;
import io.showcase.order.domain.Money;
import java.math.BigDecimal;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * PROGRAMMATIC BEAN REGISTRATION with Spring Framework 7's {@link BeanRegistrar}.
 *
 * <p>Before Spring 7 the options were {@code @Conditional*} on many {@code @Bean} methods (verbose for
 * N variants) or a {@code BeanDefinitionRegistryPostProcessor} (low-level and awkward). A
 * BeanRegistrar is plain code: loops, ifs, reading the Environment, and it's AOT/GraalVM-friendly.
 *
 * <p>Here {@code app.fraud.mode} decides which {@link FraudScreening} strategy exists at all:
 * <ul>
 *   <li>{@code lenient}: accept everything (local development)</li>
 *   <li>{@code strict}: send large orders to review and reject absurd ones</li>
 * </ul>
 * Activated with {@code @Import} on a configuration class.
 */
public class FraudScreeningRegistrar implements BeanRegistrar {

    @Override
    public void register(BeanRegistry registry, Environment env) {
        String mode = env.getProperty("app.fraud.mode", "lenient");
        long threshold = env.getProperty("app.fraud.review-threshold-amount", Long.class, 5_000L);

        if ("strict".equalsIgnoreCase(mode)) {
            registry.registerBean("fraudScreening", StrictFraudScreening.class,
                    spec -> spec.supplier(context -> new StrictFraudScreening(BigDecimal.valueOf(threshold)))
                                .description("Threshold-based fraud screening"));
        } else {
            registry.registerBean("fraudScreening", LenientFraudScreening.class);
        }
    }

    static final class LenientFraudScreening implements FraudScreening {
        @Override
        public Verdict screen(String customerId, Money total) {
            return Verdict.ACCEPT;
        }
    }

    static final class StrictFraudScreening implements FraudScreening {
        private final BigDecimal reviewThreshold;

        StrictFraudScreening(BigDecimal reviewThreshold) {
            this.reviewThreshold = reviewThreshold;
        }

        @Override
        public Verdict screen(String customerId, Money total) {
            if (total.amount().compareTo(reviewThreshold.multiply(BigDecimal.TEN)) > 0) return Verdict.REJECT;
            if (total.amount().compareTo(reviewThreshold) > 0) return Verdict.REVIEW;
            return Verdict.ACCEPT;
        }
    }

    /** Hooks the registrar into the context. */
    @Configuration(proxyBeanMethods = false)
    @Import(FraudScreeningRegistrar.class)
    static class FraudScreeningConfig {
    }
}
