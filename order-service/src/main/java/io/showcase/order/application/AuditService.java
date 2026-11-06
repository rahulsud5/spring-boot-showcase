package io.showcase.order.application;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit trail written in its OWN transaction: {@code Propagation.REQUIRES_NEW}.
 *
 * <pre>
 *   caller tx (T1) ──┐
 *                    │ suspend T1
 *                    ├── T2: INSERT audit_log ── COMMIT   ← independent of T1's fate
 *                    │ resume T1
 *   T1 ... ROLLBACK  ┘   (the audit row stays)
 * </pre>
 *
 * <p>Use cases: audit and security logs, "attempt" records, sequence or number allocation.
 * <b>Caveat:</b> T2 needs a SECOND pooled connection while T1 still holds one. With a pool of N and N
 * concurrent callers, everyone waits for a connection nobody releases: a pool deadlock. Size the
 * pool accordingly (or write audits asynchronously).
 *
 * <p>Also shows {@code JdbcClient} (Spring 6.1+): a fluent JDBC API for simple statements, without the
 * ceremony of JdbcTemplate or an entity mapping.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String principal, Map<String, ?> details) {
        jdbc.sql("INSERT INTO audit_log (action, principal, details) VALUES (:action, :principal, CAST(:details AS jsonb))")
                .param("action", action)
                .param("principal", principal)
                .param("details", json.writeValueAsString(details))
                .update();
    }
}
