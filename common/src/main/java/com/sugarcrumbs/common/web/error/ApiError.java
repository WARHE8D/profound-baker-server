package com.sugarcrumbs.common.web.error;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error body for every non-2xx response, so frontend/API
 * consumers never have to guess the shape of a failure.
 *
 * @param fieldErrors only on a 400 from Bean Validation of a request body: one entry per rejected field
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        @Nullable String path,
        @Nullable List<FieldError> fieldErrors
) {
    public static ApiError of(int status, String error, String message, @Nullable String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }

    public static ApiError withFieldErrors(int status, String error, String message, @Nullable String path,
                                           List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, path, fieldErrors);
    }

    /** One rejected request field, e.g. {@code {"field": "lines[0].quantity", "message": "must be greater than 0"}}. */
    public record FieldError(String field, String message) {
    }
}
