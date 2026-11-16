package com.sugarcrumbs.identity.web.dto;

import jakarta.validation.constraints.Size;

/** Postal address in profile requests and responses. Every field is optional. */
public record AddressDto(
        @Size(max = 200) String street,
        @Size(max = 100) String city,
        @Size(max = 100) String region,
        @Size(max = 20) String postalCode,
        @Size(max = 100) String country
) {
}
