package io.showcase.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The single public ENTRY POINT. Responsibilities that are better solved ONCE at the edge than in
 * every service:
 * <ul>
 *   <li>routing (one public host, many internal services) and path rewriting</li>
 *   <li>authentication (reject invalid JWTs before they consume service resources)</li>
 *   <li>rate limiting per user (Redis token bucket)</li>
 *   <li>circuit breaking + fallbacks, retries of idempotent requests</li>
 *   <li>correlation ids, security headers, CORS</li>
 * </ul>
 * Keep BUSINESS logic out of the gateway; otherwise it becomes a deployment bottleneck and a hidden
 * monolith.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
