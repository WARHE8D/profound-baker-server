package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Guest checkout ({@code POST /api/public/orders}): the items, one pickup slot
 * and the guest's contact details. Names and prices are never taken from the
 * client; they are copied from the catalog at checkout.
 *
 * @param pickupAt the start of a bookable slot from {@code GET /api/public/availability}
 * @param lines    each item at most once; the cap only bounds the request, #15 sets the business limit
 */
public record CheckoutRequest(
        @NotNull @Future Instant pickupAt,
        @NotNull @Valid GuestContactRequest guestContact,
        @Size(max = 1000) String notes,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid OrderLineRequest> lines
) {
}
