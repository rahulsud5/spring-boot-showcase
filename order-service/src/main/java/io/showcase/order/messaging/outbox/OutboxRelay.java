package io.showcase.order.messaging.outbox;

import io.showcase.order.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox RELAY: polls unpublished rows, publishes them to Kafka, and marks them as published.
 *
 * <p><b>Programmatic transactions</b> with {@link TransactionTemplate}: the transaction boundary is
 * explicit in code. That's useful in scheduled jobs, for fine-grained boundaries inside a loop, or when
 * the self-invocation pitfall rules out {@code @Transactional}.
 *
 * <p><b>Delivery guarantee:</b> AT LEAST ONCE. If Kafka acknowledges but the DB commit then fails, the
 * row gets published again on the next poll. Consumers deduplicate by eventId. Producer settings in
 * application.yml ({@code acks=all}, {@code enable.idempotence=true}) prevent duplicates caused by
 * producer RETRIES, and the key (= orderId) keeps all events of one order in ONE partition, in order.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactionTemplate, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.kafka = kafka;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * fixedDelay (not fixedRate): the next run starts N ms after the previous one FINISHED, so runs never
     * overlap on one instance. Across instances, SKIP LOCKED does the coordination.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:PT0.5S}")
    public void publishPending() {
        Integer published = transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = repository.lockNextBatch(properties.outbox().batchSize());
            for (OutboxEvent event : batch) {
                publish(event);
            }
            return batch.size();     // dirty checking flushes the markPublished/markFailed changes at commit
        });
        if (published != null && published > 0) {
            log.debug("Relayed {} outbox events", published);
        }
    }

    private void publish(OutboxEvent event) {
        var record = new ProducerRecord<>(event.getTopic(), event.getAggregateId(), event.getPayload());
        // Headers carry metadata without parsing the payload (routing, tracing, dedupe)
        record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(5, TimeUnit.SECONDS);       // wait for the broker ack before marking as published
            event.markPublished(clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.markFailed("interrupted");
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Publishing outbox event {} failed (attempt {})", event.getId(), event.getAttempts() + 1, e);
            event.markFailed(String.valueOf(e.getMessage()));
        }
    }

    /** Housekeeping: published rows are only useful for debugging for a while. */
    @Scheduled(cron = "${app.outbox.cleanup-cron:0 15 3 * * *}")
    public void deleteOldPublished() {
        Integer deleted = transactionTemplate.execute(status ->
                repository.deletePublishedBefore(clock.instant().minus(properties.outbox().retention())));
        log.info("Outbox cleanup removed {} published events", deleted);
    }
}
