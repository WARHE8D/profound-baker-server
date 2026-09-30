package com.sugarcrumbs.server.dto.response;

import java.time.LocalTime;

/**
 * {@code remainingCapacity} equals {@code capacity} for now — nothing
 * yet subtracts existing bookings from it. That subtraction is a
 * Sprint 4 change confined entirely to
 * {@code AvailabilityService.bookedCount(...)}; this DTO's shape
 * already anticipates it so nothing downstream (controllers, frontend)
 * needs to change when it lands.
 */
public record TimeSlotResponse(
        LocalTime startTime,
        LocalTime endTime,
        int capacity,
        int remainingCapacity,
        boolean bookable
) {
}
