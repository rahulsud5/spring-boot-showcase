package io.showcase.order.persistence;

import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JPA AUDITING: fills {@code @CreatedBy/@CreatedDate/@LastModifiedDate} automatically.
 *
 * <ul>
 *   <li>{@link AuditorAware} answers "who is the current user?", read from Spring Security's
 *       SecurityContext (the JWT subject). Kafka listener threads have no user, so we fall back to
 *       "system".</li>
 *   <li>{@link DateTimeProvider} backed by an injectable {@link Clock}: tests can freeze time instead
 *       of fighting {@code Instant.now()}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
class PersistenceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName)
                .or(() -> Optional.of("system"));
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
