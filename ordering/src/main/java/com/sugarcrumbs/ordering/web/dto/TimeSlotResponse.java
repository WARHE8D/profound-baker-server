package com.sugarcrumbs.ordering.web.dto;

import java.time.Instant;
import java.time.LocalTime;

/**
 * One pickup slot. {@code startTime}/{@code endTime} are wall-clock times at the bakery, for display;
 * {@code startsAt}/{@code endsAt} are the same moments as UTC instants, converted with the Owner's time
 * zone. A guest sends {@code pickupAt = startsAt} at checkout, so the client never converts zones and the
 * server stays the only place daylight-saving rules are applied (a time skipped by spring-forward resolves
 * to the instant after the gap; a repeated one, to its first occurrence).
 *
 * <p>{@code remainingCapacity} is {@code capacity} minus the slot's non-withdrawn orders, never below 0.
 */
public record TimeSlotResponse(
        LocalTime startTime,
        LocalTime endTime,
        Instant startsAt,
        Instant endsAt,
        int capacity,
        int remainingCapacity,
        boolean bookable
) {
}
