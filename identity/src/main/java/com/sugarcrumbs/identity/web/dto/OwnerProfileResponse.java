package com.sugarcrumbs.identity.web.dto;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** The Owner's profile. Never carries the password hash. */
public record OwnerProfileResponse(
        UUID id,
        String email,
        String businessName,
        String ownerName,
        @Nullable String phone,
        @Nullable String logoUrl,
        @Nullable AddressDto address,
        String timeZone
) {
}
