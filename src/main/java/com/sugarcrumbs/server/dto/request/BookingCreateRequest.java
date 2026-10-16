package com.sugarcrumbs.server.dto.request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record BookingCreateRequest(
        @NotNull UUID itemId,
        @Min(1) int quantity,
        @NotNull @Future Instant scheduledFor,
        @NotNull @Valid GuestContactRequest guestContact,
        @Size(max = 1000) String notes
) {
}
