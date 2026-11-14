package com.sugarcrumbs.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code /api/auth/refresh} and {@code /api/auth/logout}. */
public record RefreshRequest(
        @NotBlank @Size(max = 128) String refreshToken
) {
    @Override
    public String toString() {
        return "RefreshRequest[redacted]";
    }
}
