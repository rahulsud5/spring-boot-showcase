package io.showcase.notification;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code notification.*} settings. {@code senderAddress} can come from AWS SECRETS MANAGER: see
 * {@code spring.config.import} in application.yml. The secret's JSON keys become properties, as if
 * they were written in application.yml.
 */
@Validated
@ConfigurationProperties("notification")
public record NotificationProperties(@Email @NotBlank String senderAddress,
                                     @NotBlank String receiptsBucket,
                                     @NotBlank String opsAlertsTopic) {
}
