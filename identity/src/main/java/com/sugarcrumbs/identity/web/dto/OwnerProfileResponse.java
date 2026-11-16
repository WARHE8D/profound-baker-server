package com.sugarcrumbs.identity.web.dto;

import java.util.UUID;

/** The Owner's profile. Never carries the password hash. */
public record OwnerProfileResponse(
        UUID id,
        String email,
        String businessName,
        String ownerName,
        String phone,
        String logoUrl,
        AddressDto address,
        String timeZone
) {
}
