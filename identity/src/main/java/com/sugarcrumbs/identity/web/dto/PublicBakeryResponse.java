package com.sugarcrumbs.identity.web.dto;

import org.jspecify.annotations.Nullable;

/**
 * What a shopper may know about the bakery: enough for the storefront's header and footer, and the time
 * zone its pickup times are in. Deliberately not the Owner's profile: no id, login email or personal name.
 */
public record PublicBakeryResponse(
        String businessName,
        String timeZone,
        @Nullable String logoUrl,
        @Nullable String phone,
        @Nullable AddressDto address
) {
}
