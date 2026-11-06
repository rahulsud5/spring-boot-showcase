package io.showcase.order.application;

import io.micrometer.observation.annotation.Observed;
import io.showcase.order.application.OrderViews.OrderDetails;
import io.showcase.order.application.port.FraudScreening;
import io.showcase.order.application.port.InventoryPort;
import io.showcase.order.application.port.InventoryPort.Availability;
import io.showcase.order.application.port.PricingPort;
import io.showcase.order.domain.DiscountPolicy;
import io.showcase.order.domain.LineDraft;
import io.showcase.order.domain.Money;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * APPLICATION SERVICE / FACADE for the order use cases. It is deliberately NOT transactional.
 *
 * <p>Placing an order needs two REMOTE calls (prices over REST, stock over gRPC) plus one DB write.
 * The remote calls run here, OUTSIDE any transaction, and only the final write enters the short
 * {@link OrderCommandService#createOrder} transaction. That keeps DB connections and locks held for
 * milliseconds, not for the duration of network calls.
 *
 * <p>Java 21 + Spring features on display:
 * <ul>
 *   <li><b>Virtual threads</b> ({@code Executors.newVirtualThreadPerTaskExecutor()}): the price lookups
 *       for all lines run CONCURRENTLY with plain blocking code. No reactive operators needed; each
 *       blocked virtual thread costs a few hundred bytes, not an OS thread.</li>
 *   <li><b>Pattern matching</b> over the sealed {@code Availability} type.</li>
 *   <li><b>{@code @Retryable}</b>, built into Spring Framework 7 (it used to require spring-retry).</li>
 *   <li><b>{@code @Observed}</b> (Micrometer): ONE annotation produces a timer metric AND a trace span
 *       named "orders.place".</li>
 * </ul>
 */
@Service
public class OrderApplicationService {

    private final PricingPort pricing;
    private final InventoryPort inventory;
    private final FraudScreening fraudScreening;
    private final OrderCommandService commands;
    private final OrderMetrics metrics;
    private final JsonMapper json;

    public OrderApplicationService(PricingPort pricing, InventoryPort inventory, FraudScreening fraudScreening,
                                   OrderCommandService commands, OrderMetrics metrics, JsonMapper json) {
        this.pricing = pricing;
        this.inventory = inventory;
        this.fraudScreening = fraudScreening;
        this.commands = commands;
        this.metrics = metrics;
        this.json = json;
    }

    @Observed(name = "orders.place", contextualName = "place-order")
    public OrderDetails placeOrder(PlaceOrderCommand command) {
        // 1. Prices: one remote call per SKU, fanned out on virtual threads
        List<LineDraft> drafts = priceAllLines(command.items());

        // 2. Stock pre-check over gRPC (protected by a circuit breaker inside the adapter)
        Availability availability = inventory.checkAvailability(command.items().stream()
                .map(i -> new InventoryPort.RequestedItem(i.sku(), i.quantity())).toList());
        switch (availability) {
            case Availability.Unavailable(List<String> skus) -> throw new InsufficientStockException(skus);
            case Availability.Available available -> { /* happy path */ }
            case Availability.Unknown(String reason) -> metrics.degradedPlacement(reason);   // accept; the saga decides
        }

        // 3. Fraud screening (the implementation is chosen at startup by FraudScreeningRegistrar)
        Money subtotal = drafts.stream().map(LineDraft::lineTotal).reduce(Money.zero(drafts.getFirst().unitPrice().currency()), Money::plus);
        if (fraudScreening.screen(command.customerId(), subtotal) == FraudScreening.Verdict.REJECT) {
            throw new OrderRejectedException("Order rejected by fraud screening");
        }

        // 4. The ONLY transactional step: a short write
        OrderDetails created = commands.createOrder(command.customerId(), command.shippingAddress(), drafts,
                discountFor(command.discountCode()), json.writeValueAsString(command.metadata()));
        metrics.orderPlaced(created.currency(), created.total());
        return created;
    }

    /**
     * RETRY ON OPTIMISTIC LOCK CONFLICT. The retry must wrap the WHOLE transaction: a retry inside a
     * failed transaction would reuse a persistence context that holds stale data. That's why
     * {@code @Retryable} sits here, on a different bean than {@code @Transactional}.
     * {@code jitter} randomizes the delays so competing clients don't retry in lockstep.
     */
    @Retryable(includes = ObjectOptimisticLockingFailureException.class, maxRetries = 3, delay = 50, multiplier = 2, jitter = 25)
    public OrderDetails ship(UUID orderId) {
        return commands.ship(orderId);
    }

    public OrderDetails cancel(UUID orderId, String reason, String actor) {
        return commands.cancel(orderId, reason, actor);
    }

    private List<LineDraft> priceAllLines(List<PlaceOrderCommand.Item> items) {
        // try-with-resources: close() waits for all tasks. One VIRTUAL thread per task, no pool sizing needed.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<LineDraft>> futures = items.stream()
                    .map(item -> executor.submit(() -> new LineDraft(item.sku(), item.quantity(), pricing.priceOf(item.sku()))))
                    .toList();
            return futures.stream().map(OrderApplicationService::join).toList();
        }
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();          // never swallow interrupts
            throw new IllegalStateException("Interrupted while pricing", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;                           // unwrap so the global handler sees the real error
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Maps promo codes to discount strategies (the codes would normally live in a table). */
    static DiscountPolicy discountFor(String code) {
        if (code == null) {
            return new DiscountPolicy.None();
        }
        return switch (code.toUpperCase()) {
            case "WELCOME10" -> new DiscountPolicy.PercentageOff(10);
            case "COFFEE3FOR2" -> new DiscountPolicy.BuyXGetYFree("SKU-COFFEE", 2, 1);
            case "VIP" -> new DiscountPolicy.LoyaltyTier("GOLD", 5);
            default -> new DiscountPolicy.None();
        };
    }
}
