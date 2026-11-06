package io.showcase.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * MongoDB integration test with Testcontainers. The MongoDB container runs as a single-node REPLICA
 * SET, so multi-document transactions work exactly as in production.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@ActiveProfiles("test")
@Testcontainers
class StockServiceIT {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0");

    @MockitoBean
    KafkaTemplate<String, String> kafka;      // no broker needed for these tests

    @Autowired
    ReservationFacade reservations;
    @Autowired
    StockService stock;
    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeEach
    void stock() {
        mongoTemplate.dropCollection(StockItem.class);
        mongoTemplate.dropCollection(Reservation.class);
        mongoTemplate.save(new StockItem("SKU-A", 5));
        mongoTemplate.save(new StockItem("SKU-B", 1));
    }

    @Test
    void reservationIsAllOrNothing() {
        var result = reservations.reserve("order-1", List.of(new Reservation.Line("SKU-A", 2), new Reservation.Line("SKU-B", 3)));

        assertThat(result).isInstanceOf(StockService.ReservationResult.Rejected.class);
        // SKU-A's decrement was ROLLED BACK by the multi-document transaction
        assertThat(stock.levels(List.of("SKU-A")).get("SKU-A").getAvailable()).isEqualTo(5);
    }

    @Test
    void reservingTheSameOrderTwiceIsIdempotent() {
        reservations.reserve("order-2", List.of(new Reservation.Line("SKU-A", 2)));
        reservations.reserve("order-2", List.of(new Reservation.Line("SKU-A", 2)));      // redelivered event

        assertThat(stock.levels(List.of("SKU-A")).get("SKU-A").getAvailable()).isEqualTo(3);
    }

    @Test
    void concurrentOrdersNeverOversell() throws Exception {
        // 20 virtual threads race for 5 units: the atomic conditional update lets exactly 5 succeed
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<StockService.ReservationResult>> tasks = IntStream.range(0, 20)
                    .<Callable<StockService.ReservationResult>>mapToObj(i -> () ->
                            reservations.reserve("race-" + i, List.of(new Reservation.Line("SKU-A", 1))))
                    .toList();
            long reserved = executor.invokeAll(tasks).stream()
                    .map(f -> { try { return f.get(); } catch (Exception e) { throw new IllegalStateException(e); } })
                    .filter(r -> r instanceof StockService.ReservationResult.Reserved)
                    .count();
            assertThat(reserved).isEqualTo(5);
        }
        assertThat(stock.levels(List.of("SKU-A")).get("SKU-A").getAvailable()).isZero();
    }

    @Test
    void releaseReturnsStockOnce() {
        reservations.reserve("order-3", List.of(new Reservation.Line("SKU-A", 4)));

        assertThat(stock.release("order-3")).isTrue();
        assertThat(stock.release("order-3")).isFalse();          // compensation is idempotent too
        assertThat(stock.levels(List.of("SKU-A")).get("SKU-A").getAvailable()).isEqualTo(5);
    }
}
