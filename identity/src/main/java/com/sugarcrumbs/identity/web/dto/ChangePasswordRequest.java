package com.sugarcrumbs.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/owner/password}. The 12-character minimum matches the initial
 * password rule (OwnerBootstrap); 128 caps what is fed to BCrypt.
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(min = 12, max = 128) String newPassword
) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
