package com.sugarcrumbs.identity.web.dto;

import java.time.Instant;

/**
 * Returned by login and refresh. {@code expiresIn} is the access token's lifetime in seconds
 * (OAuth2 style); the refresh token replaces the one the client held before.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {
    @Override
    public String toString() {
        return "TokenResponse[redacted]";
    }
}
