package com.sugarcrumbs.common.exception;

import java.time.Duration;

/** A client exceeded a rate limit (429). {@code retryAfter} becomes the {@code Retry-After} header. */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(Duration retryAfter) {
        super("too many requests; try again later");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
