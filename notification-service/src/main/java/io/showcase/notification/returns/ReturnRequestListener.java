package io.showcase.notification.returns;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AMAZON SQS consumer with Spring Cloud AWS's {@code @SqsListener}: the AWS-native queue, compared with
 * RabbitMQ in the same service.
 *
 * <ul>
 *   <li>The message is converted from JSON to the record automatically.</li>
 *   <li>A normal return DELETES the message (ack). An exception leaves it on the queue; it becomes
 *       visible again after the VISIBILITY TIMEOUT and is retried. After maxReceiveCount (3, see the
 *       redrive policy in docker/localstack/init-aws.sh) SQS moves it to the DLQ.</li>
 *   <li>Standard queues are at-least-once and unordered; FIFO queues add ordering per message group and
 *       deduplication, at lower throughput.</li>
 *   <li>Long polling, batching and back-pressure are handled by the listener container.</li>
 * </ul>
 */
@Component
class ReturnRequestListener {

    private static final Logger log = LoggerFactory.getLogger(ReturnRequestListener.class);

    record ReturnRequest(String orderId, String sku, String reason) {}

    @SqsListener("return-requests")
    void onReturnRequest(ReturnRequest request) {
        log.info("Return requested for order {} sku {}: {}", request.orderId(), request.sku(), request.reason());
        // e.g. create an RMA, email return instructions, notify the warehouse...
    }
}
