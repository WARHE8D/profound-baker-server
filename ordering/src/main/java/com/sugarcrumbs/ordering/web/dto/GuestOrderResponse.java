package com.sugarcrumbs.ordering.web.dto;

import com.sugarcrumbs.ordering.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A guest's own order, as their magic link shows it. Only what the guest already gave or is owed:
 * no internal id, no audit timestamps beyond when it was placed, and nothing about any other order.
 *
 * @param cancellableUntil until when {@code POST .../cancel} works; {@code null} once the order is
 *                         past cancelling online whatever the time (in preparation, or over)
 */
public record GuestOrderResponse(
        String reference,
        OrderStatus status,
        Instant pickupAt,
        List<OrderLineResponse> lines,
        BigDecimal subtotal,
        GuestContactResponse guestContact,
        String notes,
        String rejectionReason,
        String cancellationReason,
        Instant placedAt,
        Instant cancellableUntil
) {
}
