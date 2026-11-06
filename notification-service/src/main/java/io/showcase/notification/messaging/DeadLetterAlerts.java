package io.showcase.notification.messaging;

import io.awspring.cloud.sns.core.SnsTemplate;
import io.showcase.contracts.messaging.Messaging.Rabbit;
import io.showcase.notification.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Watches the DEAD-LETTER QUEUE and alerts operations through AWS SNS (which fans out to email,
 * Slack via Lambda, PagerDuty...).
 *
 * <p>{@link SnsTemplate} (Spring Cloud AWS): a Spring-style template over the AWS SDK v2 SNS client. The
 * topic is resolved by NAME to its ARN automatically. A dead letter is a symptom; the alert carries the
 * x-death header (reason, count, original queue) so on-call engineers can triage it.
 */
@Component
class DeadLetterAlerts {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAlerts.class);

    private final SnsTemplate sns;
    private final NotificationProperties properties;

    DeadLetterAlerts(SnsTemplate sns, NotificationProperties properties) {
        this.sns = sns;
        this.properties = properties;
    }

    @RabbitListener(queues = Rabbit.EMAIL_DLQ)
    void onDeadLetter(Message message) {
        Object death = message.getMessageProperties().getHeaders().get("x-death");
        log.error("Notification dead-lettered: messageId={} x-death={}", message.getMessageProperties().getMessageId(), death);
        sns.sendNotification(properties.opsAlertsTopic(),
                "Notification " + message.getMessageProperties().getMessageId() + " dead-lettered: " + death,
                "[showcase] notification dead-lettered");
    }
}
