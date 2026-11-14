package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.BlockedDate;
import com.sugarcrumbs.ordering.domain.WorkingHours;
import com.sugarcrumbs.ordering.persistence.BlockedDateRepository;
import com.sugarcrumbs.ordering.persistence.BookingRepository;
import com.sugarcrumbs.ordering.persistence.WorkingHoursRepository;
import com.sugarcrumbs.ordering.web.dto.DayAvailabilityResponse;
import com.sugarcrumbs.ordering.web.dto.TimeSlotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Slot math: working hours + blocked dates + current bookings give per-slot remaining capacity. */
class AvailabilityServiceTests {

    /** A Tuesday. */
    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    private final WorkingHoursRepository workingHoursRepository = mock(WorkingHoursRepository.class);
    private final BlockedDateRepository blockedDateRepository = mock(BlockedDateRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final OwnerApi ownerApi = mock(OwnerApi.class);
    private final AvailabilityService service =
            new AvailabilityService(workingHoursRepository, blockedDateRepository, bookingRepository, ownerApi);

    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(ownerApi.currentOwnerId()).thenReturn(ownerId);
        when(blockedDateRepository.findAllByOwnerIdOrderByStartDateAsc(ownerId)).thenReturn(List.of());
    }

    @Test
    void splitsTheWindowIntoBackToBackSlots() {
        open("09:00", "11:00", 30, 2);

        DayAvailabilityResponse day = service.computeForDate(DAY);

        assertThat(day.open()).isTrue();
        assertThat(day.slots()).extracting(TimeSlotResponse::startTime)
                .containsExactly(t("09:00"), t("09:30"), t("10:00"), t("10:30"));
        assertThat(day.slots().getLast().endTime()).isEqualTo(t("11:00"));
        assertThat(day.slots()).allSatisfy(slot -> {
            assertThat(slot.capacity()).isEqualTo(2);
            assertThat(slot.remainingCapacity()).isEqualTo(2);
            assertThat(slot.bookable()).isTrue();
        });
    }

    @Test
    void aPartialTrailingSlotIsNotOffered() {
        open("09:00", "10:40", 30, 1);

        assertThat(service.computeForDate(DAY).slots()).extracting(TimeSlotResponse::endTime)
                .containsExactly(t("09:30"), t("10:00"), t("10:30"));
    }

    @Test
    void aSlotLongerThanTheWindowLeavesTheDayOpenButEmpty() {
        open("09:00", "09:20", 30, 1);

        DayAvailabilityResponse day = service.computeForDate(DAY);

        assertThat(day.open()).isTrue();
        assertThat(day.slots()).isEmpty();
    }

    @Test
    // SEPARATE_THREAD: the old loop never checked for interrupts, so a same-thread timeout hung the build.
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void slotsNeverRunPastMidnight() {
        // 23:30 + 45 min wraps to 00:15, which is "before" 23:50; this used to loop forever.
        open("22:00", "23:50", 45, 1);

        assertThat(service.computeForDate(DAY).slots()).extracting(TimeSlotResponse::startTime)
                .containsExactly(t("22:00"), t("22:45"));
    }

    @Test
    // SEPARATE_THREAD: the old loop never checked for interrupts, so a same-thread timeout hung the build.
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aWindowEndingJustBeforeMidnightGetsNoSlotEndingAtMidnight() {
        open("23:00", "23:59", 30, 1);

        assertThat(service.computeForDate(DAY).slots()).extracting(TimeSlotResponse::endTime)
                .containsExactly(t("23:30"));
    }

    @Test
    void bookingsReduceRemainingCapacityAndAFullSlotIsNotBookable() {
        open("09:00", "10:00", 30, 2);
        when(bookingRepository.countByScheduledForGreaterThanEqualAndScheduledForLessThanAndStatusNot(any(), any(), any()))
                .thenReturn(1L, 2L);

        List<TimeSlotResponse> slots = service.computeForDate(DAY).slots();

        assertThat(slots.get(0).remainingCapacity()).isEqualTo(1);
        assertThat(slots.get(0).bookable()).isTrue();
        assertThat(slots.get(1).remainingCapacity()).isZero();
        assertThat(slots.get(1).bookable()).isFalse();
    }

    @Test
    void anOverbookedSlotReportsZeroRemainingNotANegativeNumber() {
        open("09:00", "09:30", 30, 1);
        when(bookingRepository.countByScheduledForGreaterThanEqualAndScheduledForLessThanAndStatusNot(any(), any(), any()))
                .thenReturn(3L);

        assertThat(service.computeForDate(DAY).slots().getFirst().remainingCapacity()).isZero();
    }

    @Test
    void rescheduleChecksLeaveTheBookingItselfOutOfTheCount() {
        open("09:00", "09:30", 30, 1);
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.countByScheduledForGreaterThanEqualAndScheduledForLessThanAndStatusNotAndIdNot(
                any(), any(), any(), eq(bookingId))).thenReturn(0L);
        when(bookingRepository.countByScheduledForGreaterThanEqualAndScheduledForLessThanAndStatusNot(any(), any(), any()))
                .thenReturn(1L);

        assertThat(service.computeForDate(DAY, bookingId).slots().getFirst().bookable()).isTrue();
        assertThat(service.computeForDate(DAY).slots().getFirst().bookable()).isFalse();
    }

    @Test
    void aDayWithoutWorkingHoursIsClosed() {
        when(workingHoursRepository.findByOwnerIdAndDayOfWeek(ownerId, DayOfWeek.TUESDAY)).thenReturn(Optional.empty());

        DayAvailabilityResponse day = service.computeForDate(DAY);

        assertThat(day.open()).isFalse();
        assertThat(day.reason()).isEqualTo("Closed on TUESDAY");
        assertThat(day.slots()).isEmpty();
    }

    @Test
    void aBlockedDateClosesTheDayEvenWithWorkingHours() {
        open("09:00", "17:00", 30, 1);
        when(blockedDateRepository.findAllByOwnerIdOrderByStartDateAsc(ownerId)).thenReturn(List.of(
                BlockedDate.builder().ownerId(ownerId).startDate(DAY).endDate(DAY).reason("Holiday").build()));

        DayAvailabilityResponse day = service.computeForDate(DAY);

        assertThat(day.open()).isFalse();
        assertThat(day.reason()).isEqualTo("Holiday");
        assertThat(day.slots()).isEmpty();
    }

    @Test
    void rangeReturnsEveryDayInclusive() {
        when(workingHoursRepository.findByOwnerIdAndDayOfWeek(any(), any())).thenReturn(Optional.empty());

        assertThat(service.computeForRange(DAY, DAY.plusDays(2))).containsOnlyKeys(DAY, DAY.plusDays(1), DAY.plusDays(2));
    }

    @Test
    void rangeIsCappedAtSixtyTwoDaysAndMustRunForwards() {
        when(workingHoursRepository.findByOwnerIdAndDayOfWeek(any(), any())).thenReturn(Optional.empty());

        assertThat(service.computeForRange(DAY, DAY.plusDays(61))).hasSize(62);
        assertThatIllegalArgumentException().isThrownBy(() -> service.computeForRange(DAY, DAY.plusDays(62)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.computeForRange(DAY, DAY.minusDays(1)));
    }

    private void open(String start, String end, int slotMinutes, int capacity) {
        WorkingHours hours = WorkingHours.builder()
                .ownerId(ownerId)
                .dayOfWeek(DayOfWeek.TUESDAY)
                .startTime(t(start))
                .endTime(t(end))
                .slotDurationMinutes(slotMinutes)
                .maxConcurrentBookings(capacity)
                .build();
        when(workingHoursRepository.findByOwnerIdAndDayOfWeek(ownerId, DayOfWeek.TUESDAY)).thenReturn(Optional.of(hours));
    }

    private static LocalTime t(String time) {
        return LocalTime.parse(time);
    }
}
