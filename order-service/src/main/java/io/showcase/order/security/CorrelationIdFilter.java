package io.showcase.order.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A plain SERVLET FILTER (not part of the security chain): it runs for every request, before security.
 *
 * <p>It propagates a business CORRELATION ID (set by the gateway, or generated here) into the MDC, so
 * every log line of the request carries it, and echoes it back in the response. OpenTelemetry trace
 * ids already do this technically; a correlation id is the human-friendly id support teams paste
 * into tickets.
 *
 * <p>Untrusted input: the header is validated against a strict pattern before it reaches logs, which
 * prevents LOG INJECTION (CRLF or forged log lines, OWASP A09).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");      // threads are reused (or pooled): always clean up ThreadLocals
        }
    }
}
