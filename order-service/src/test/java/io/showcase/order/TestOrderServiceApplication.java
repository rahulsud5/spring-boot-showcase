package io.showcase.order;

import org.springframework.boot.SpringApplication;

/**
 * DEV-TIME LAUNCHER (Spring Boot 3.1+): run this main() from the IDE to start order-service with
 * Postgres, Kafka, RabbitMQ and Redis in Testcontainers. There's no docker compose, no local installs,
 * and the infrastructure is the same as in the tests. (Run it with "demo" as the active profile to
 * also load demo data.)
 */
public class TestOrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(OrderServiceApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
