package com.sugarcrumbs.server.dto.response;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

public record WorkingHoursResponse(
        UUID id,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        int slotDurationMinutes,
        int maxConcurrentBookings
) {
}

