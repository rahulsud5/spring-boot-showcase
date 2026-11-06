package io.showcase.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.showcase.contracts.messaging.NotificationCommand;
import io.showcase.notification.archive.ReceiptArchiver;
import io.showcase.notification.email.EmailSender;
import io.showcase.notification.email.TemplateRenderer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

class NotificationListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final EmailSender email = mock(EmailSender.class);
    private final ReceiptArchiver archiver = mock(ReceiptArchiver.class);
    private final NotificationListener listener = new NotificationListener(json, new TemplateRenderer(), email, archiver);

    private Message message(NotificationCommand command) {
        return new Message(json.writeValueAsBytes(command), new MessageProperties());
    }

    @Test
    void sendsEmailAndArchivesReceiptForConfirmedOrders() {
        var command = new NotificationCommand(UUID.randomUUID(), "ann@example.com", "order-confirmed", Map.of("orderId", "o-1", "total", "10 EUR"));

        listener.onNotification(message(command));

        verify(email).send(eq("ann@example.com"), eq("Your order is confirmed"), anyString());
        verify(archiver).archiveReceipt(command);
    }

    @Test
    void duplicateDeliveriesAreSentOnlyOnce() {
        var command = new NotificationCommand(UUID.randomUUID(), "ann@example.com", "order-cancelled", Map.of("orderId", "o-2"));

        listener.onNotification(message(command));
        listener.onNotification(message(command));

        verify(email, times(1)).send(any(), any(), any());
    }

    @Test
    void unknownTemplateFailsSoTheMessageIsDeadLettered() {
        var command = new NotificationCommand(UUID.randomUUID(), "ann@example.com", "nope", Map.of());
        assertThatThrownBy(() -> listener.onNotification(message(command))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masksEmailAddressesInLogs() {
        assertThat(NotificationListener.mask("annabelle@example.com")).isEqualTo("a***@example.com");
    }
}
