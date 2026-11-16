package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.BlockedDate;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.domain.WorkingHours;
import com.sugarcrumbs.ordering.persistence.BlockedDateRepository;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import com.sugarcrumbs.ordering.persistence.WorkingHoursRepository;
import com.sugarcrumbs.ordering.web.dto.DayAvailabilityResponse;
import com.sugarcrumbs.ordering.web.dto.TimeSlotResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.UUID;

/**
 * The single place that answers "can a guest order for this day/slot?" —
 * every other part of the app (checkout, the owner's calendar) should call
 * this rather than combining {@link WorkingHours}, {@link BlockedDate} and
 * order occupancy themselves.
 *
 * <p>Every non-cancelled order in a slot takes one place of its capacity,
 * however many lines it has. When {@code OrderService.reschedule} checks an
 * order's <em>new</em> time, that order's own row would otherwise count against
 * itself; {@link #computeForDate(LocalDate, UUID)} takes its id to leave it out.
 *
 * <p>Working hours are local times in the Owner's time zone ({@link OwnerApi#settings()}),
 * while orders are stored as instants; each slot is converted with that zone, so the
 * server's own zone never matters.
 */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    /** Hard cap on a range query so nobody can request a 10-year calendar in one call. */
    private static final int MAX_RANGE_DAYS = 62;

    private final WorkingHoursRepository workingHoursRepository;
    private final BlockedDateRepository blockedDateRepository;
    private final OrderRepository orderRepository;
    private final OwnerApi ownerApi;

    public AvailabilityService(WorkingHoursRepository workingHoursRepository,
                               BlockedDateRepository blockedDateRepository,
                               OrderRepository orderRepository,
                               OwnerApi ownerApi) {
        this.workingHoursRepository = workingHoursRepository;
        this.blockedDateRepository = blockedDateRepository;
        this.orderRepository = orderRepository;
        this.ownerApi = ownerApi;
    }

    public DayAvailabilityResponse computeForDate(LocalDate date) {
        return computeForDate(date, null);
    }

    /** @param excludeOrderId an order to leave out of its own slot's occupancy count — see class javadoc */
    public DayAvailabilityResponse computeForDate(LocalDate date, UUID excludeOrderId) {
        UUID ownerId = ownerApi.currentOwnerId();
        return computeForDate(ownerId, date, blockedDateRepository.findAllByOwnerIdOrderByStartDateAsc(ownerId),
                excludeOrderId, ownerApi.settings().timeZone());
    }

    public Map<LocalDate, DayAvailabilityResponse> computeForRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("'to' cannot be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("range cannot exceed " + MAX_RANGE_DAYS + " days");
        }

        UUID ownerId = ownerApi.currentOwnerId();
        List<BlockedDate> blockedDates = blockedDateRepository.findAllByOwnerIdOrderByStartDateAsc(ownerId);
        ZoneId zone = ownerApi.settings().timeZone();

        Map<LocalDate, DayAvailabilityResponse> result = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            result.put(date, computeForDate(ownerId, date, blockedDates, null, zone));
        }
        return result;
    }

    private DayAvailabilityResponse computeForDate(UUID ownerId, LocalDate date, List<BlockedDate> blockedDates,
                                                   UUID excludeOrderId, ZoneId zone) {
        BlockedDate blocking = blockedDates.stream()
                .filter(bd -> bd.covers(date))
                .findFirst()
                .orElse(null);
        if (blocking != null) {
            String reason = blocking.getReason() != null ? blocking.getReason() : "Unavailable";
            return DayAvailabilityResponse.closed(date, reason);
        }

        WorkingHours hours = workingHoursRepository.findByOwnerIdAndDayOfWeek(ownerId, date.getDayOfWeek()).orElse(null);
        if (hours == null) {
            return DayAvailabilityResponse.closed(date, "Closed on " + date.getDayOfWeek());
        }

        return DayAvailabilityResponse.open(date, generateSlots(hours, date, excludeOrderId, zone));
    }

    /** Slots that don't evenly divide the working window are simply not offered — no partial trailing slot. */
    private List<TimeSlotResponse> generateSlots(WorkingHours hours, LocalDate date, UUID excludeOrderId,
                                                 ZoneId zone) {
        List<TimeSlotResponse> slots = new ArrayList<>();
        LocalTime cursor = hours.getStartTime();
        while (true) {
            LocalTime slotEnd = cursor.plusMinutes(hours.getSlotDurationMinutes());
            // LocalTime wraps at midnight, so a slot running past it "ends" before it starts (23:30 + 45 min = 00:15).
            // It can't fit a window that ends the same day, and comparing 00:15 against the window end alone would
            // accept it and then keep generating slots forever.
            if (!slotEnd.isAfter(cursor) || slotEnd.isAfter(hours.getEndTime())) {
                break;
            }
            int capacity = hours.getMaxConcurrentBookings();
            int booked = bookedCount(date, cursor, slotEnd, excludeOrderId, zone);
            int remaining = Math.max(0, capacity - booked);
            slots.add(new TimeSlotResponse(cursor, slotEnd, capacity, remaining, remaining > 0));
            cursor = slotEnd;
        }
        return slots;
    }

    private int bookedCount(LocalDate date, LocalTime slotStart, LocalTime slotEnd, UUID excludeOrderId,
                            ZoneId zone) {
        Instant slotStartInstant = date.atTime(slotStart).atZone(zone).toInstant();
        Instant slotEndInstant = date.atTime(slotEnd).atZone(zone).toInstant();

        if (excludeOrderId == null) {
            return (int) orderRepository.countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusNot(
                    slotStartInstant, slotEndInstant, OrderStatus.CANCELLED);
        }
        return (int) orderRepository.countByPickupAtGreaterThanEqualAndPickupAtLessThanAndStatusNotAndIdNot(
                slotStartInstant, slotEndInstant, OrderStatus.CANCELLED, excludeOrderId);
    }
}
