package com.sugarcrumbs.server.dto.error;


import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Uniform error body for every non-2xx response, so frontend/API
 * consumers never have to guess the shape of a failure.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<Map<String, String>> fieldErrors
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }

    public static ApiError withFieldErrors(int status, String error, String message, String path,
                                           List<Map<String, String>> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, path, fieldErrors);
    }
}