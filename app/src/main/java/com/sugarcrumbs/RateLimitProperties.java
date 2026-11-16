package com.sugarcrumbs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Per-client-IP limits ({@code app.rate-limit.*}) on the endpoints an attacker
 * would hammer: login (password guessing) and guest checkout (slot and stock
 * squatting). Each limit is a token bucket: {@code capacity} requests, refilled
 * gradually over {@code period}.
 */
@ConfigurationProperties("app.rate-limit")
record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Limit login,
        @DefaultValue Limit checkout
) {

    RateLimitProperties {
        login = login.orDefault(5, Duration.ofMinutes(1));
        checkout = checkout.orDefault(10, Duration.ofMinutes(10));
    }

    /** {@code capacity} requests per {@code period}; unset fields take the policy's default. */
    record Limit(Integer capacity, Duration period) {

        Limit orDefault(int defaultCapacity, Duration defaultPeriod) {
            Limit limit = new Limit(capacity != null ? capacity : defaultCapacity,
                    period != null ? period : defaultPeriod);
            if (limit.capacity() < 1 || limit.period().isNegative() || limit.period().isZero()) {
                throw new IllegalArgumentException("a rate limit needs capacity >= 1 and a positive period");
            }
            return limit;
        }
    }
}
