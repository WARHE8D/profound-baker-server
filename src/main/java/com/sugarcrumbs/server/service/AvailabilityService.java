package com.sugarcrumbs.server.service;

import com.sugarcrumbs.server.dto.response.DayAvailabilityResponse;
import com.sugarcrumbs.server.dto.response.TimeSlotResponse;
import com.sugarcrumbs.server.entity.BlockedDate;
import com.sugarcrumbs.server.entity.BookingStatus;
import com.sugarcrumbs.server.entity.Owner;
import com.sugarcrumbs.server.entity.WorkingHours;
import com.sugarcrumbs.server.repository.booking.BookingRepository;
import com.sugarcrumbs.server.repository.owner.BlockedDateRepository;
import com.sugarcrumbs.server.repository.owner.WorkingHoursRepository;
import com.sugarcrumbs.server.util.BusinessTimeZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The single place that answers "can a customer book this day/slot?" —
 * every other part of the app (browse-and-book flow, owner's calendar
 * view) should call this rather than combining {@link WorkingHours},
 * {@link BlockedDate} and {@link com.sugarcrumbs.server.entity.Booking} occupancy themselves.
 *
 * <p>As of Sprint 4, {@link #bookedCount} is wired to the real
 * {@link BookingRepository} — every non-cancelled booking in a slot
 * counts against that slot's capacity. The one wrinkle: when
 * {@code BookingService.reschedule} checks whether a booking's *new*
 * time is free, that booking's own (about-to-change) row would
 * otherwise count against itself. {@link #computeForDate(LocalDate, UUID)}
 * exists for exactly that case — pass the booking's own id to exclude it
 * from its own capacity count.
 */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    /** Hard cap on a range query so nobody can request a 10-year calendar in one call. */
    private static final int MAX_RANGE_DAYS = 62;

    private final WorkingHoursRepository workingHoursRepository;
    private final BlockedDateRepository blockedDateRepository;
    private final BookingRepository bookingRepository;
    private final OwnerService ownerService;

    public AvailabilityService(WorkingHoursRepository workingHoursRepository,
                               BlockedDateRepository blockedDateRepository,
                               BookingRepository bookingRepository,
                               OwnerService ownerService) {
        this.workingHoursRepository = workingHoursRepository;
        this.blockedDateRepository = blockedDateRepository;
        this.bookingRepository = bookingRepository;
        this.ownerService = ownerService;
    }

    public DayAvailabilityResponse computeForDate(LocalDate date) {
        return computeForDate(date, null);
    }

    /** @param excludeBookingId a booking to leave out of its own slot's occupancy count — see class javadoc */
    public DayAvailabilityResponse computeForDate(LocalDate date, UUID excludeBookingId) {
        Owner owner = ownerService.getTheOwner();
        return computeForDate(owner, date, blockedDateRepository.findAllByOwnerOrderByStartDateAsc(owner), excludeBookingId);
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
            result.put(date, computeForDate(owner, date, blockedDates, null));
        }
        return result;
    }

    private DayAvailabilityResponse computeForDate(Owner owner, LocalDate date, List<BlockedDate> blockedDates,
                                                   UUID excludeBookingId) {
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

        return DayAvailabilityResponse.open(date, generateSlots(hours, date, excludeBookingId));
    }

    /** Slots that don't evenly divide the working window are simply not offered — no partial trailing slot. */
    private List<TimeSlotResponse> generateSlots(WorkingHours hours, LocalDate date, UUID excludeBookingId) {
        List<TimeSlotResponse> slots = new ArrayList<>();
        LocalTime cursor = hours.getStartTime();
        while (true) {
            LocalTime slotEnd = cursor.plusMinutes(hours.getSlotDurationMinutes());
            if (slotEnd.isAfter(hours.getEndTime())) {
                break;
            }
            int capacity = hours.getMaxConcurrentBookings();
            int booked = bookedCount(date, cursor, slotEnd, excludeBookingId);
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

    private int bookedCount(LocalDate date, LocalTime slotStart, LocalTime slotEnd, UUID excludeBookingId) {
        Instant slotStartInstant = date.atTime(slotStart).atZone(BusinessTimeZone.ZONE).toInstant();
        Instant slotEndInstant = date.atTime(slotEnd).atZone(BusinessTimeZone.ZONE).toInstant();

        if (excludeBookingId == null) {
            return (int) bookingRepository.countByScheduledForGreaterThanEqualAndScheduledForLessThanAndStatusNot(
                    slotStartInstant, slotEndInstant, BookingStatus.CANCELLED);
        }
        return (int) bookingRepository.countByScheduledForGreaterThanEqualAndScheduledForLessThanAndStatusNotAndIdNot(
                slotStartInstant, slotEndInstant, BookingStatus.CANCELLED, excludeBookingId);
    }
}
