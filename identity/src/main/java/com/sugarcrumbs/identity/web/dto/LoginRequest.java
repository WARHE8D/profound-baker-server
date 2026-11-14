package com.sugarcrumbs.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The password cap keeps a huge body from being fed to BCrypt (which only reads 72 bytes anyway). */
public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String password
) {
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=redacted]";
    }
}
