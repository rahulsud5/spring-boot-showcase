package io.showcase.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real infrastructure for tests, started by TESTCONTAINERS in Docker.
 *
 * <p>{@code @ServiceConnection} (Spring Boot 3.1+): Boot inspects each container and creates the
 * matching {@code ConnectionDetails} beans (JDBC URL, Kafka bootstrap servers, Redis host/port...).
 * There's no {@code @DynamicPropertySource} boilerplate. For a {@link GenericContainer}, {@code name}
 * tells Boot which service it is.
 *
 * <p>Declaring containers as BEANS makes Spring manage their lifecycle, and the cached test context
 * reuses them across test classes: they start once per JVM, not per class. The same configuration
 * powers {@link TestOrderServiceApplication}, a dev-time launcher with zero local setup.
 *
 * <p>Why not H2 or embedded fakes? This service uses JSONB, partial and GIN indexes, SKIP LOCKED,
 * window functions and a trigger. H2 supports none of it in Postgres' dialect. Test against what you
 * run in production.
 *
 * <p>Testcontainers 2.x: container classes moved to per-module packages
 * ({@code org.testcontainers.postgresql.PostgreSQLContainer}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }
}
