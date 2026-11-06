package io.showcase.order.web;

import io.showcase.order.application.OrderViews.OrderDetails;
import io.showcase.order.persistence.OrderReportingRepository.StatusHistoryEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * HTTP request and response DTOs. They're separate from the domain and application types on purpose:
 * the API contract evolves independently, and a refactoring inside the service never silently
 * changes the JSON clients depend on.
 *
 * <p><b>Bean Validation</b> ({@code @Valid} on the controller parameter) rejects bad input with 400
 * BEFORE any business code runs. That's the first line of defense against injection, overflow and
 * garbage data (OWASP: input validation). Nested objects need {@code @Valid} to be validated too.
 */
public final class OrderApiDtos {

    private OrderApiDtos() {
    }

    public record PlaceOrderRequest(
            @NotNull @Valid AddressDto shippingAddress,
            @NotEmpty @Size(max = 50) List<@Valid ItemDto> items,
            @Nullable @Pattern(regexp = "[A-Z0-9]{3,20}") String discountCode,
            @Nullable @Size(max = 10) Map<@Size(max = 40) String, @Size(max = 200) String> metadata) {
    }

    public record ItemDto(@Sku String sku, @Min(1) @Max(100) int quantity) {
    }

    public record AddressDto(
            @NotBlank @Size(max = 200) String street,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 country code") String country) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** v2 of GET /api/orders/{id}: the same order plus its full status timeline. */
    public record OrderResponseV2(OrderDetails order, List<StatusHistoryEntry> history) {
    }
}
