package com.sugarcrumbs.server.service;

import com.sugarcrumbs.server.dto.response.DayAvailabilityResponse;
import com.sugarcrumbs.server.dto.response.TimeSlotResponse;
import com.sugarcrumbs.server.entity.BlockedDate;
import com.sugarcrumbs.server.entity.Owner;
import com.sugarcrumbs.server.entity.WorkingHours;
import com.sugarcrumbs.server.repository.owner.BlockedDateRepository;
import com.sugarcrumbs.server.repository.owner.WorkingHoursRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single place that answers "can a customer book this day/slot?" —
 * every other part of the app (browse-and-book flow, owner's calendar
 * view) should call this rather than combining {@link WorkingHours} and
 * {@link BlockedDate} themselves. Centralizing it here is what the
 * Sprint 3 planning notes call out specifically: this logic must not be
 * duplicated across endpoints.
 *
 * <p><b>Sprint 4 extension point:</b> {@link #bookedCount} always
 * returns 0 right now because no {@code BookingRepository} exists yet.
 * Once it does, that one method is the only thing that needs to change
 * — everything else here (the DTOs, the slot-generation loop, the
 * controllers) already accounts for a non-zero booked count via
 * {@code remainingCapacity}/{@code bookable}.
 */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    /** Hard cap on a range query so nobody can request a 10-year calendar in one call. */
    private static final int MAX_RANGE_DAYS = 62;

    private final WorkingHoursRepository workingHoursRepository;
    private final BlockedDateRepository blockedDateRepository;
    private final OwnerService ownerService;

    public AvailabilityService(WorkingHoursRepository workingHoursRepository,
                               BlockedDateRepository blockedDateRepository,
                               OwnerService ownerService) {
        this.workingHoursRepository = workingHoursRepository;
        this.blockedDateRepository = blockedDateRepository;
        this.ownerService = ownerService;
    }

    public DayAvailabilityResponse computeForDate(LocalDate date) {
        Owner owner = ownerService.getTheOwner();
        return computeForDate(owner, date, blockedDateRepository.findAllByOwnerOrderByStartDateAsc(owner));
    }

    public Map<LocalDate, DayAvailabilityResponse> computeForRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' cannot be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("range cannot exceed " + MAX_RANGE_DAYS + " days");
        }

        Owner owner = ownerService.getTheOwner();
        List<BlockedDate> blockedDates = blockedDateRepository.findAllByOwnerOrderByStartDateAsc(owner);

        Map<LocalDate, DayAvailabilityResponse> result = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            result.put(date, computeForDate(owner, date, blockedDates));
        }
        return result;
    }

    private DayAvailabilityResponse computeForDate(Owner owner, LocalDate date, List<BlockedDate> blockedDates) {
        BlockedDate blocking = blockedDates.stream()
                .filter(bd -> bd.covers(date))
                .findFirst()
                .orElse(null);
        if (blocking != null) {
            String reason = blocking.getReason() != null ? blocking.getReason() : "Unavailable";
            return DayAvailabilityResponse.closed(date, reason);
        }

        WorkingHours hours = workingHoursRepository.findByOwnerAndDayOfWeek(owner, date.getDayOfWeek()).orElse(null);
        if (hours == null) {
            return DayAvailabilityResponse.closed(date, "Closed on " + date.getDayOfWeek());
        }

        return DayAvailabilityResponse.open(date, generateSlots(hours, date));
    }

    /** Slots that don't evenly divide the working window are simply not offered — no partial trailing slot. */
    private List<TimeSlotResponse> generateSlots(WorkingHours hours, LocalDate date) {
        List<TimeSlotResponse> slots = new ArrayList<>();
        LocalTime cursor = hours.getStartTime();
        while (true) {
            LocalTime slotEnd = cursor.plusMinutes(hours.getSlotDurationMinutes());
            if (slotEnd.isAfter(hours.getEndTime())) {
                break;
            }
            int capacity = hours.getMaxConcurrentBookings();
            int booked = bookedCount(date, cursor);
            int remaining = Math.max(0, capacity - booked);
            slots.add(new TimeSlotResponse(cursor, slotEnd, capacity, remaining, remaining > 0));
            cursor = slotEnd;
            if (cursor.equals(LocalTime.MIDNIGHT)) {
                // plusMinutes wrapped past midnight (a working window ending exactly at end-of-day) — stop instead of looping forever.
                break;
            }
        }
        return slots;
    }

    /**
     * TODO (Sprint 4): once {@code BookingRepository} exists, replace this
     * with a real count of non-cancelled bookings whose
     * {@code scheduledFor} falls in [{@code slotStart}, {@code slotStart}
     * + slot duration) on {@code date}. Returning 0 here means every slot
     * currently reports full capacity as remaining — correct for a
     * calendar with no bookings yet, intentionally incomplete once real
     * bookings exist.
     */
    private int bookedCount(LocalDate date, LocalTime slotStart) {
        return 0;
    }
}
