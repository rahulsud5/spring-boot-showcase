package io.showcase.inventory.config;

import io.showcase.contracts.messaging.Messaging.Topics;
import io.showcase.inventory.catalog.Product;
import io.showcase.inventory.catalog.ProductRepository;
import io.showcase.inventory.stock.StockItem;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Infrastructure wiring for inventory-service.
 */
@Configuration(proxyBeanMethods = false)
@EnableMongoAuditing
class InventoryConfig {

    private static final Logger log = LoggerFactory.getLogger(InventoryConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Spring Boot does NOT auto-configure a Mongo transaction manager, because transactions need a
     * replica set and change semantics. Declaring it makes {@code @Transactional} work for MongoDB
     * (multi-document ACID transactions, MongoDB 4.0+).
     *
     * <p>Note: with BOTH Kafka transactions (transaction-id-prefix, so Boot adds a KafkaTransactionManager) and
     * this bean, there are TWO transaction managers. {@code @Primary} makes plain {@code @Transactional} use
     * MongoDB, while the Kafka listener container uses the Kafka one. Use
     * {@code @Transactional("kafkaTransactionManager")} to pick explicitly.
     */
    @Bean(name = "transactionManager")
    @Primary
    MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory);
    }

    @Bean
    NewTopic inventoryEventsTopic() {
        return TopicBuilder.name(Topics.INVENTORY_EVENTS).partitions(6).replicas(1).build();
    }

    /**
     * Demo catalog: SKU-0000 … SKU-0199 (matching order-service's demo data) + SKU-COFFEE.
     * {@code @Profile("!test")}: integration tests create exactly the data they need.
     * {@code ApplicationRunner} beans run after startup, when the context is ready.
     */
    @Bean
    @Profile("!test")
    ApplicationRunner seedCatalog(ProductRepository products, MongoTemplate mongo) {
        return args -> {
            if (products.count() > 0) {
                return;
            }
            List<Product> catalog = new ArrayList<>();
            String[] categories = {"shoes", "jackets", "bags", "accessories"};
            for (int i = 0; i < 200; i++) {
                String sku = "SKU-%04d".formatted(i);
                String category = categories[i % categories.length];
                catalog.add(new Product(sku, "Product " + i, "A fine " + category + " item number " + i, category,
                        new Product.Price(BigDecimal.valueOf(10 + (i % 50) * 3L, 0).add(new BigDecimal("0.90")), "EUR"),
                        List.of(category, i % 2 == 0 ? "outdoor" : "city"),
                        Map.of("color", i % 3 == 0 ? "red" : "black", "weightGrams", 200 + i)));
            }
            catalog.add(new Product("SKU-COFFEE", "Coffee Beans 1kg", "Single-origin arabica", "food",
                    new Product.Price(new BigDecimal("12.50"), "EUR"), List.of("food"), Map.of("roast", "medium")));
            products.saveAll(catalog);
            catalog.forEach(p -> mongo.save(new StockItem(p.getSku(), 100)));
            log.info("Seeded {} products with stock", catalog.size());
        };
    }
}
