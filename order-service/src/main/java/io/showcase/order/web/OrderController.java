package io.showcase.order.web;

import io.showcase.order.application.OrderApplicationService;
import io.showcase.order.application.OrderQueryService;
import io.showcase.order.application.OrderViews.CursorPage;
import io.showcase.order.application.OrderViews.OrderDetails;
import io.showcase.order.application.OrderViews.OrderSummary;
import io.showcase.order.application.PlaceOrderCommand;
import io.showcase.order.domain.Address;
import io.showcase.order.domain.OrderStatus;
import io.showcase.order.persistence.OrderReportingRepository.DailyRevenue;
import io.showcase.order.persistence.OrderSearchCriteria;
import io.showcase.order.web.IdempotencyService.Outcome;
import io.showcase.order.web.OrderApiDtos.CancelRequest;
import io.showcase.order.web.OrderApiDtos.OrderResponseV2;
import io.showcase.order.web.OrderApiDtos.PlaceOrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST API for orders.
 *
 * <p>REST design choices shown here:
 * <ul>
 *   <li><b>Resource-oriented URLs + HTTP semantics</b>: POST creates (201 Created + Location header),
 *       GET reads (cacheable), state changes that aren't plain field updates are modeled as
 *       sub-resources ({@code POST /orders/{id}/cancel}), which is clearer than PATCH with a status
 *       field.</li>
 *   <li><b>Idempotency-Key</b> on POST: safe client retries.</li>
 *   <li><b>API versioning</b> (Spring 7): {@code version = "2"} selects the handler by the
 *       {@code X-API-Version} header.</li>
 *   <li><b>Pagination</b>: cursor-based for the customer feed (infinite scroll), page-based for the
 *       admin search (jump to page N).</li>
 *   <li><b>Errors</b>: RFC 9457 Problem Details (GlobalExceptionHandler); no stack traces leak.</li>
 *   <li><b>Thin controller</b>: translate HTTP to a use case and back. No business logic here.</li>
 *   <li>{@code @Validated} on the class enables constraint annotations on simple parameters
 *       ({@code @Min} on {@code size}); {@code @Valid} validates request bodies.</li>
 * </ul>
 * The caller's identity comes from the validated JWT ({@code @AuthenticationPrincipal Jwt}), NEVER
 * from a request field. A body field "customerId" would let anyone order as anyone.
 */
@RestController
@RequestMapping("/api/orders")
@Validated
class OrderController {

    private final OrderApplicationService orders;
    private final OrderQueryService queries;
    private final IdempotencyService idempotency;

    OrderController(OrderApplicationService orders, OrderQueryService queries, IdempotencyService idempotency) {
        this.orders = orders;
        this.queries = queries;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<OrderDetails> place(@Valid @RequestBody PlaceOrderRequest request,
                                       @RequestHeader("Idempotency-Key") @Size(min = 8, max = 64) String idempotencyKey,
                                       @AuthenticationPrincipal Jwt jwt) {
        String customerId = jwt.getSubject();

        // Pattern-matching switch STATEMENT on the sealed Outcome: replay, conflict, or proceed.
        // (A switch EXPRESSION can't contain "return"; it must yield a value or throw.)
        Outcome outcome = idempotency.begin(customerId, idempotencyKey);
        switch (outcome) {
            case Outcome.Completed(UUID existingOrderId) -> {
                return ResponseEntity.ok(queries.get(existingOrderId));             // replay: same result, 200
            }
            case Outcome.InProgress inProgress -> throw new IdempotencyService.RequestInProgressException();
            case Outcome.Started s -> { /* first request: continue below */ }
        }
        Outcome.Started started = (Outcome.Started) outcome;

        try {
            OrderDetails created = orders.placeOrder(toCommand(customerId, request));
            idempotency.complete(started, created.id());
            URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(created.id()).toUri();
            return ResponseEntity.created(location).body(created);
        } catch (RuntimeException e) {
            idempotency.abandon(started);      // let the client retry with the same key
            throw e;
        }
    }

    /** Version 1 (the default when no X-API-Version header is sent). */
    @GetMapping(path = "/{id}", version = "1")
    ResponseEntity<OrderDetails> getV1(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())                  // personal data: never cache in shared caches
                .body(queries.get(id));
    }

    /** Version 2: adds the status history. Selected with the header "X-API-Version: 2". */
    @GetMapping(path = "/{id}", version = "2")
    OrderResponseV2 getV2(@PathVariable UUID id) {
        return new OrderResponseV2(queries.get(id), queries.history(id));
    }

    /** The caller's own orders, newest first, with keyset (cursor) pagination. */
    @GetMapping("/mine")
    CursorPage<OrderSummary> mine(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(required = false) @Nullable String cursor,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.ordersOf(jwt.getSubject(), cursor, size);
    }

    /** Back-office search. Only ADMINs may search across customers. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    PagedModel<OrderSummary> search(@RequestParam(required = false) @Nullable String customerId,
                                    @RequestParam(required = false) @Nullable OrderStatus status,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable Instant to,
                                    @RequestParam(required = false) @Nullable BigDecimal minTotal,
                                    @RequestParam(required = false) @Nullable String sku,
                                    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        var criteria = new OrderSearchCriteria(customerId, status, from, to, minTotal, sku);
        return new PagedModel<>(queries.search(criteria, pageable));
    }

    @PostMapping("/{id}/cancel")
    OrderDetails cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request, @AuthenticationPrincipal Jwt jwt) {
        queries.get(id);   // reuse the @PostAuthorize ownership check: you can only cancel your own order
        return orders.cancel(id, request.reason(), jwt.getSubject());
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasRole('ADMIN')")
    OrderDetails ship(@PathVariable UUID id) {
        return orders.ship(id);
    }

    @GetMapping("/reports/daily-revenue")
    List<DailyRevenue> dailyRevenue(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queries.dailyRevenue(from, to);     // @PreAuthorize("hasRole('ADMIN')") lives on the service method
    }

    private static PlaceOrderCommand toCommand(String customerId, PlaceOrderRequest r) {
        var a = r.shippingAddress();
        return new PlaceOrderCommand(
                customerId,
                new Address(a.street(), a.city(), a.postalCode(), a.country()),
                r.items().stream().map(i -> new PlaceOrderCommand.Item(i.sku(), i.quantity())).toList(),
                r.discountCode(),
                r.metadata());
    }
}
