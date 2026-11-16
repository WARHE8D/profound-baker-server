package com.sugarcrumbs;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sugarcrumbs.RateLimitProperties.Limit;
import com.sugarcrumbs.common.exception.TooManyRequestsException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Token-bucket rate limits per client IP (Bucket4j) on login, guest checkout and
 * the guest's magic-link routes (CLAUDE.md §5). A rejected request gets a 429 {@code ApiError} with
 * {@code Retry-After}; it never reaches the controller, so a password guess
 * past the limit is not even checked.
 *
 * <p>The client IP is {@code request.getRemoteAddr()}. In prod Tomcat replaces it
 * with the {@code X-Forwarded-For} address set by Caddy, trusting that header only
 * from internal addresses ({@code server.forward-headers-strategy=native}), so a
 * client cannot pick its own bucket by sending the header itself.
 *
 * <p>Buckets live in memory, bounded and evicted when idle: right for one
 * instance. Several instances would need a shared store (Bucket4j has JDBC and
 * Redis backends) or limits at the gateway (stage 2).
 */
class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Plenty for a small bakery's clients; the least recently used buckets go first when full. */
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final List<Policy> policies;
    private final HandlerExceptionResolver resolver;
    private final Cache<String, Bucket> buckets;

    RateLimitFilter(RateLimitProperties properties, HandlerExceptionResolver resolver) {
        // The matcher Spring Security uses: it matches the decoded path, so /api/auth/%6Cogin is still login.
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        this.policies = List.of(
                new Policy("login", paths.matcher(HttpMethod.POST, "/api/auth/login"), properties.login()),
                new Policy("checkout", paths.matcher(HttpMethod.POST, "/api/public/orders"), properties.checkout()),
                // Any method: viewing and cancelling share one bucket, so probing for tokens costs the same either way.
                new Policy("magic-link", paths.matcher("/api/public/orders/by-token/**"), properties.magicLink()));
        this.resolver = resolver;

        // An idle bucket may only be dropped once it would have refilled completely anyway;
        // dropping it sooner would hand the client a fresh allowance.
        Duration idleExpiry = policies.stream().map(policy -> policy.limit().period())
                .max(Duration::compareTo).orElseThrow();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_CLIENTS)
                .expireAfterAccess(idleExpiry.compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofMinutes(1) : idleExpiry)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Policy policy = policies.stream().filter(p -> p.matcher().matches(request)).findFirst().orElse(null);
        if (policy == null) {
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = buckets.get(policy.name() + '|' + request.getRemoteAddr(), key -> newBucket(policy.limit()));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        // No IP in the log: it is personal data (CLAUDE.md §6).
        log.info("Rate limit '{}' exceeded", policy.name());
        resolver.resolveException(request, response, null,
                new TooManyRequestsException(Duration.ofNanos(probe.getNanosToWaitForRefill())));
    }

    private static Bucket newBucket(Limit limit) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period())
                        .build())
                .build();
    }

    private record Policy(String name, RequestMatcher matcher, Limit limit) {
    }
}
