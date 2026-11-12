package com.sugarcrumbs.ordering.web.dto;

import com.sugarcrumbs.ordering.domain.BookingStatus;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        ItemSummaryResponse item,
        int quantity,
        Instant scheduledFor,
        BookingStatus status,
        GuestContactResponse guestContact,
        String notes,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt
) {
}
