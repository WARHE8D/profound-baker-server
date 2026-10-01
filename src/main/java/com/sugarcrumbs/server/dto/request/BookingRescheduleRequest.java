package com.sugarcrumbs.server.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record BookingRescheduleRequest(
        @NotNull @Future Instant scheduledFor
) {
}
