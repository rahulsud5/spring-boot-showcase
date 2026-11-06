package io.showcase.order.messaging.kafka;

/** A message whose eventType isn't in the contract: non-retryable, so it goes straight to the DLT. */
public class UnknownEventTypeException extends RuntimeException {

    public UnknownEventTypeException(String eventType) {
        super("Unknown event type: " + eventType);
    }
}
