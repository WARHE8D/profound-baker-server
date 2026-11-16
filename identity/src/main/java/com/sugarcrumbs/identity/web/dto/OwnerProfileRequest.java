package com.sugarcrumbs.identity.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/owner/profile}: replaces every editable field (omitted optional
 * fields are cleared). The login email and password are changed through their own flows.
 *
 * @param timeZone IANA zone id such as {@code Europe/London}; fixed offsets are refused
 */
public record OwnerProfileRequest(
        @NotBlank @Size(max = 150) String businessName,
        @NotBlank @Size(max = 150) String ownerName,
        @Size(max = 30) String phone,
        @Size(max = 300) String logoUrl,
        @Valid AddressDto address,
        @NotBlank @Size(max = 64) String timeZone
) {
}
