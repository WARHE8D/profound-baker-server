package com.sugarcrumbs.server.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record WorkingHoursRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @Min(5) int slotDurationMinutes,
        @Min(1) int maxConcurrentBookings
) {
}
