package io.showcase.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of order-service.
 *
 * <h2>What {@code @SpringBootApplication} really is</h2>
 * A meta-annotation combining three annotations:
 * <ol>
 *   <li>{@code @SpringBootConfiguration}: this class is a {@code @Configuration} source of bean definitions.</li>
 *   <li>{@code @EnableAutoConfiguration}: Boot loads every auto-configuration class listed in
 *       {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} of each jar.
 *       Each one is guarded by conditions ({@code @ConditionalOnClass}, {@code @ConditionalOnMissingBean},
 *       {@code @ConditionalOnProperty}...). Put Hikari + the PostgreSQL driver on the classpath and a
 *       {@code DataSource} appears; define your own {@code DataSource} bean and Boot BACKS OFF.
 *       Start with {@code --debug} to print the CONDITIONS EVALUATION REPORT (what matched, and why
 *       not), or visit /actuator/conditions.</li>
 *   <li>{@code @ComponentScan}: finds {@code @Component/@Service/@Repository/@Controller} classes in
 *       THIS package and its sub-packages, which is why the main class sits in the root package.</li>
 * </ol>
 *
 * <h2>Startup sequence ({@code SpringApplication.run})</h2>
 * Create the {@code Environment} (properties from application.yml, profile files, env vars, command-line
 * args, config trees, in a well-defined precedence order) → create the ApplicationContext → register
 * bean definitions (scan + auto-config) → refresh: instantiate singletons and apply
 * BeanPostProcessors (which create the proxies for @Transactional, @Cacheable, @Async, @Retryable,
 * security...) → start the embedded Tomcat → publish ApplicationReadyEvent → mark the readiness state
 * ACCEPTING_TRAFFIC.
 */
@SpringBootApplication
@ConfigurationPropertiesScan   // registers every @ConfigurationProperties record in the scanned packages
@EnableScheduling              // activates @Scheduled methods (OutboxRelay)
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
