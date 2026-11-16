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
 * @param lines    each item at most once; at most {@code app.order.max-lines} lines (default 20) of at most
 *                 {@code app.order.max-quantity-per-line} units each (default 100), checked by {@code OrderService}
 */
public record CheckoutRequest(
        @NotNull @Future Instant pickupAt,
        @NotNull @Valid GuestContactRequest guestContact,
        @Size(max = 1000) String notes,
        @NotEmpty List<@NotNull @Valid OrderLineRequest> lines
) {
}
