package io.showcase.notification.archive;

import io.awspring.cloud.s3.S3Template;
import io.showcase.contracts.messaging.NotificationCommand;
import io.showcase.notification.NotificationProperties;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Archives order receipts in AMAZON S3 through Spring Cloud AWS's {@link S3Template}.
 *
 * <ul>
 *   <li>{@code store(bucket, key, object)} serializes the object to JSON and uploads it.</li>
 *   <li>The key layout {@code receipts/yyyy/MM/dd/<orderId>.json} spreads objects across prefixes
 *       (S3 scales request rates PER PREFIX) and makes lifecycle rules ("move to Glacier after 90
 *       days") easy to target.</li>
 *   <li>The key is deterministic (orderId), so a redelivered message overwrites the same object.
 *       The upload is naturally IDEMPOTENT.</li>
 *   <li>PRESIGNED URLs grant time-limited read access to one object without making the bucket public
 *       or proxying the bytes through our service.</li>
 * </ul>
 * Credentials: none in code. Locally they're static LocalStack keys from application.yml; on AWS the
 * SDK's default credential chain picks up the pod's IAM role (IRSA / EKS Pod Identity / ECS task role).
 */
@Component
public class ReceiptArchiver {

    private final S3Template s3;
    private final NotificationProperties properties;

    public ReceiptArchiver(S3Template s3, NotificationProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    public String archiveReceipt(NotificationCommand command) {
        String key = "receipts/%s/%s.json".formatted(LocalDate.now().toString().replace('-', '/'),
                command.variables().getOrDefault("orderId", command.commandId().toString()));
        s3.store(properties.receiptsBucket(), key, command.variables());
        return key;
    }

    public URL presignedDownloadUrl(String key) {
        return s3.createSignedGetURL(properties.receiptsBucket(), key, Duration.ofMinutes(15));
    }
}
