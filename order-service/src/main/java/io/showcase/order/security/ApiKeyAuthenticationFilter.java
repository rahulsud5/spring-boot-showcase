package io.showcase.order.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CUSTOM SECURITY FILTER: API-key authentication for partner webhooks.
 *
 * <p>Implementation notes that matter for security:
 * <ul>
 *   <li>Only HASHES of valid keys are configured (SHA-256). A leaked config file doesn't leak usable
 *       keys. (High-entropy random keys don't need a slow password hash such as bcrypt.)</li>
 *   <li>{@link MessageDigest#isEqual} compares in CONSTANT TIME. {@code String.equals} returns early on
 *       the first different byte, and measuring response times lets an attacker guess a key byte by
 *       byte (a timing attack).</li>
 *   <li>A missing or invalid key does NOT respond here; it just leaves the request unauthenticated.
 *       The AuthorizationFilter later answers 401 or 403 consistently.</li>
 *   <li>Scoped by {@link #shouldNotFilter} to /api/webhooks/** only.</li>
 * </ul>
 *
 * <p><b>Pitfall:</b> it is deliberately NOT a {@code @Component}. Spring Boot automatically registers
 * every {@code Filter} bean in the SERVLET container too, so it would run twice: once in the security
 * chain and once outside it. Either construct it inside the security config (as we do) or disable the
 * auto-registration with a {@code FilterRegistrationBean} where {@code setEnabled(false)}.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    private final List<byte[]> validKeyHashes;

    ApiKeyAuthenticationFilter(List<String> sha256HexHashes) {
        this.validKeyHashes = sha256HexHashes.stream().map(HexFormat.of()::parseHex).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && isValid(presented)) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "payment-partner", null, AuthorityUtils.createAuthorityList("ROLE_PARTNER"));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private boolean isValid(String presentedKey) {
        byte[] presentedHash = sha256(presentedKey);
        boolean match = false;
        for (byte[] valid : validKeyHashes) {
            match |= MessageDigest.isEqual(valid, presentedHash);   // no early exit: constant work for every key
        }
        return match;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
        }
    }
}
