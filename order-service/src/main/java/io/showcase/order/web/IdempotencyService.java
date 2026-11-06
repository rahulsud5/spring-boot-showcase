package io.showcase.order.web;

import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * IDEMPOTENT POST with an {@code Idempotency-Key} header, stored in REDIS.
 *
 * <p><b>The problem:</b> the client sends POST /orders, the network times out, and the client retries.
 * Did the first request create an order? Without idempotency, retries create DUPLICATE ORDERS (and
 * duplicate charges). Stripe, PayPal and AWS APIs all solve this the same way:
 *
 * <pre>
 *  1st request:  SET idem:alice:K "IN_PROGRESS" NX EX 86400  → OK          → process, then store the order id
 *  retry (done): SET ... NX                                  → (nil) + GET → order id → return the SAME order (200)
 *  concurrent:   SET ... NX                                  → (nil) + GET → IN_PROGRESS → 409, retry later
 *  failure:      DEL idem:alice:K                            → the client may retry with the same key
 * </pre>
 *
 * {@code SET NX EX} is a single ATOMIC Redis command: exactly one request wins the race, with no
 * Lua script or distributed lock library needed. Keys are scoped per customer, so one customer
 * can't probe another's keys.
 */
@Component
public class IdempotencyService {

    static final Duration TTL = Duration.ofHours(24);
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Outcome begin(String scope, String idempotencyKey) {
        String key = "idem:order-service:" + scope + ":" + idempotencyKey;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, IN_PROGRESS, TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return new Outcome.Started(key);
        }
        String value = redis.opsForValue().get(key);
        if (value == null || IN_PROGRESS.equals(value)) {
            return new Outcome.InProgress();
        }
        return new Outcome.Completed(UUID.fromString(value));
    }

    public void complete(Outcome.Started started, UUID orderId) {
        redis.opsForValue().set(started.redisKey(), orderId.toString(), TTL);
    }

    public void abandon(Outcome.Started started) {
        redis.delete(started.redisKey());
    }

    public sealed interface Outcome {
        record Started(String redisKey) implements Outcome {}
        record InProgress() implements Outcome {}
        record Completed(UUID orderId) implements Outcome {}
    }

    /** Thrown for a concurrent duplicate; mapped to 409 Conflict. */
    public static class RequestInProgressException extends RuntimeException {
        public RequestInProgressException() {
            super("A request with this Idempotency-Key is still being processed");
        }
    }
}
