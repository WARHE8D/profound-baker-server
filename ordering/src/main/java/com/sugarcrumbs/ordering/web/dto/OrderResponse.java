package com.sugarcrumbs.ordering.web.dto;

import com.sugarcrumbs.ordering.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        String reference,
        Instant pickupAt,
        OrderStatus status,
        List<OrderLineResponse> lines,
        BigDecimal subtotal,
        GuestContactResponse guestContact,
        String notes,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt
) {
}
