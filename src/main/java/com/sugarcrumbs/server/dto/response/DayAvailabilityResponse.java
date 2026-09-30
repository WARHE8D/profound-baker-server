package com.sugarcrumbs.server.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * The single source of truth a customer (or the owner's calendar UI)
 * should query before offering a booking time — already reconciles
 * working hours against blocked dates, so callers never have to
 * combine those two sources themselves.
 */
public record DayAvailabilityResponse(
        LocalDate date,
        boolean open,
        String reason,
        List<TimeSlotResponse> slots
) {
    public static DayAvailabilityResponse closed(LocalDate date, String reason) {
        return new DayAvailabilityResponse(date, false, reason, List.of());
    }

    public static DayAvailabilityResponse open(LocalDate date, List<TimeSlotResponse> slots) {
        return new DayAvailabilityResponse(date, true, null, slots);
    }
}

