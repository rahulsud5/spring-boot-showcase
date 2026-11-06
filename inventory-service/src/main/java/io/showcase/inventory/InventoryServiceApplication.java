package io.showcase.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * inventory-service: product catalog + stock levels in MONGODB, exposed over gRPC (stock) and REST
 * (catalog), reacting to order events from Kafka.
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
