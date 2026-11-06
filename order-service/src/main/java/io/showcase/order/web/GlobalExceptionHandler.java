package io.showcase.order.web;

import io.showcase.order.application.InsufficientStockException;
import io.showcase.order.application.OrderRejectedException;
import io.showcase.order.domain.IllegalOrderStateException;
import io.showcase.order.domain.OrderNotFoundException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * CENTRALIZED ERROR HANDLING producing RFC 9457 "Problem Details" ({@code application/problem+json}):
 *
 * <pre>{@code
 * {
 *   "type":   "https://api.showcase.example/problems/order-state-conflict",
 *   "title":  "Order state conflict",
 *   "status": 409,
 *   "detail": "Order 0192... cannot move from DELIVERED to CANCELLED",
 *   "instance": "/api/orders/0192.../cancel"
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Extending {@link ResponseEntityExceptionHandler} gives standard Problem Details for Spring MVC's
 *       own exceptions too: validation errors (400), unsupported media type (415), method not allowed
 *       (405)... (spring.mvc.problemdetails.enabled).</li>
 *   <li>A STABLE {@code type} URI per error kind lets clients branch on it, not on message text.</li>
 *   <li><b>Security (OWASP A10 "Mishandling of Exceptional Conditions", A02 "Security
 *       Misconfiguration")</b>: unexpected exceptions return a generic message. Stack traces, SQL and
 *       class names never reach the client; they go to the logs, correlated by trace id.</li>
 * </ul>
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://api.showcase.example/problems/";

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "order-not-found", "Order not found", e.getMessage());
    }

    @ExceptionHandler(IllegalOrderStateException.class)
    ProblemDetail stateConflict(IllegalOrderStateException e) {
        return problem(HttpStatus.CONFLICT, "order-state-conflict", "Order state conflict", e.getMessage());
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail concurrentModification(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent modification",
                "The order was modified concurrently. Reload and retry.");
    }

    @ExceptionHandler(IdempotencyService.RequestInProgressException.class)
    ProblemDetail inProgress(IdempotencyService.RequestInProgressException e) {
        return problem(HttpStatus.CONFLICT, "request-in-progress", "Request in progress", e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficientStock(InsufficientStockException e) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-stock", "Insufficient stock", e.getMessage());
        pd.setProperty("unavailableSkus", e.skus());      // extension member: machine-readable details
        return pd;
    }

    @ExceptionHandler(OrderRejectedException.class)
    ProblemDetail rejected(OrderRejectedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "order-rejected", "Order rejected", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
    }

    /** Let Spring Security turn this into 403 (and keep its audit events) instead of the catch-all below. */
    @ExceptionHandler(AccessDeniedException.class)
    void accessDenied(AccessDeniedException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);                  // full detail in the logs (with trace id)...
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "An unexpected error occurred.");             // ...nothing sensitive in the response
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + type));
        pd.setTitle(title);
        return pd;
    }
}
