package io.showcase.notification.email;

import io.showcase.notification.NotificationProperties;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

/**
 * Sends email through Spring's {@link MailSender} abstraction. With spring-cloud-aws-starter-ses on the
 * classpath, the injected implementation is Amazon SES (SimpleEmailServiceMailSender). Swap the starter
 * for spring-boot-starter-mail and the same code sends via SMTP. That's the ADAPTER pattern, delivered
 * by the framework.
 */
@Component
public class EmailSender {

    private final MailSender mailSender;
    private final NotificationProperties properties;

    public EmailSender(MailSender mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void send(String to, String subject, String body) {
        var message = new SimpleMailMessage();
        message.setFrom(properties.senderAddress());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
