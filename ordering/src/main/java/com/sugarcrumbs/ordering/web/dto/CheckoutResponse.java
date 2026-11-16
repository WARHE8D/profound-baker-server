package com.sugarcrumbs.ordering.web.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * The checkout response: the order, plus the guest's magic-link token alongside its fields.
 * This is the only response that ever contains the token, and there is no email yet, so the
 * SPA must show it (or the link built from it) prominently for the guest to keep.
 *
 * @param accessToken 43 URL-safe characters for {@code /api/public/orders/by-token/{token}}
 */
public record CheckoutResponse(
        @JsonUnwrapped OrderResponse order,
        String accessToken
) {
}
