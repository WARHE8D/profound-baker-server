package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RescheduleRequest(
        @NotNull @Future Instant pickupAt
) {
}
