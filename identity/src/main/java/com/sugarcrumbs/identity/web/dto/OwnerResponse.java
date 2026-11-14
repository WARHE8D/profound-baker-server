package com.sugarcrumbs.identity.web.dto;

import java.util.UUID;

/** The signed-in Owner as {@code GET /api/auth/me} returns it. Never carries the password hash. */
public record OwnerResponse(
        UUID id,
        String email,
        String ownerName,
        String businessName
) {
}
