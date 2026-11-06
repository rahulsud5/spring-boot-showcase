package io.showcase.order.messaging.kafka;

import io.showcase.contracts.messaging.Messaging.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

/**
 * Kafka setup beyond the properties in application.yml.
 *
 * <h2>Error handling: retries, then a DEAD LETTER TOPIC</h2>
 * When a listener throws, the {@link DefaultErrorHandler}:
 * <ol>
 *   <li>re-seeks the partition and RETRIES the record with exponential back-off (1 s, 2 s, 4 s).
 *       These retries BLOCK the partition: the order of events per key is kept.</li>
 *   <li>after the last attempt, the {@link DeadLetterPublishingRecoverer} publishes the record to
 *       {@code <topic>-dlt} (with exception headers) and commits the offset. One poison message no
 *       longer stalls the whole partition forever.</li>
 *   <li>NON-RETRYABLE exceptions (malformed JSON, unknown event type) skip the retries: retrying a
 *       message that can never be parsed only wastes time.</li>
 * </ol>
 * Alternative: {@code @RetryableTopic} gives NON-blocking retries through retry topics. That means
 * higher throughput, but it gives up per-key ordering. Pick based on whether ordering matters (here it
 * does: confirm and cancel events of one order must not overtake each other).
 *
 * <p>Boot detects the single {@link CommonErrorHandler} bean and plugs it into the auto-configured
 * listener container factory.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    /**
     * Topics declared as beans are created at startup by Boot's KafkaAdmin (if missing). In production,
     * topics are usually managed as code elsewhere (Terraform, Strimzi CRDs); here docker compose's
     * kafka-init also creates them. Both are idempotent.
     */
    @Bean
    NewTopic orderEventsTopic() {
        return TopicBuilder.name(Topics.ORDER_EVENTS)
                .partitions(6)                 // the upper bound on consumer parallelism within one group
                .replicas(1)                   // 3 in production, with min.insync.replicas=2 and acks=all
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    NewTopic inventoryEventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.INVENTORY_EVENTS + Topics.DLT_SUFFIX).partitions(1).replicas(1).build();
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        // Route failed records to "<topic>-dlt", partition 0 (the DLT has a single partition)
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, 0));

        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(UnknownEventTypeException.class, JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
