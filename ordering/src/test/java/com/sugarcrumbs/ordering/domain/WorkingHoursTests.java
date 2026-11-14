package com.sugarcrumbs.ordering.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WorkingHours' invariants match the ordering schema's CHECK constraints. Slot generation is in AvailabilityServiceTests. */
class WorkingHoursTests {

    private static WorkingHours.Builder valid() {
        return WorkingHours.builder()
                .ownerId(UUID.randomUUID())
                .dayOfWeek(DayOfWeek.TUESDAY)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0));
    }

    @Test
    void defaultsToHalfHourSlotsOfOne() {
        WorkingHours hours = valid().build();

        assertThat(hours.getSlotDurationMinutes()).isEqualTo(30);
        assertThat(hours.getMaxConcurrentBookings()).isEqualTo(1);
    }

    @Test
    void requiresOwnerAndDay() {
        assertThatThrownBy(() -> valid().ownerId(null).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> valid().dayOfWeek(null).build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theWindowMustStartBeforeItEnds() {
        assertThatIllegalArgumentException().isThrownBy(() -> valid().endTime(LocalTime.of(9, 0)).build());
        assertThatIllegalArgumentException().isThrownBy(() -> valid().endTime(LocalTime.of(8, 0)).build());
        assertThatThrownBy(() -> valid().startTime(null).build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rescheduleKeepsTheOldWindowWhenTheNewOneIsInvalid() {
        WorkingHours hours = valid().build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> hours.reschedule(LocalTime.of(18, 0), LocalTime.of(10, 0)));
        assertThat(hours.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(hours.getEndTime()).isEqualTo(LocalTime.of(17, 0));

        hours.reschedule(LocalTime.of(7, 30), LocalTime.of(12, 0));
        assertThat(hours.getStartTime()).isEqualTo(LocalTime.of(7, 30));
    }

    @ParameterizedTest
    @ValueSource(ints = {-30, 0, 1, 4})
    void slotsAreAtLeastFiveMinutes(int minutes) {
        assertThatIllegalArgumentException().isThrownBy(() -> valid().slotDurationMinutes(minutes).build());

        WorkingHours hours = valid().build();
        assertThatIllegalArgumentException().isThrownBy(() -> hours.changeSlotDuration(minutes));
        assertThat(hours.getSlotDurationMinutes()).isEqualTo(30);
    }

    @Test
    void aFiveMinuteSlotIsAllowed() {
        assertThat(valid().slotDurationMinutes(5).build().getSlotDurationMinutes()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void capacityIsAtLeastOne(int capacity) {
        assertThatIllegalArgumentException().isThrownBy(() -> valid().maxConcurrentBookings(capacity).build());

        WorkingHours hours = valid().maxConcurrentBookings(3).build();
        assertThatIllegalArgumentException().isThrownBy(() -> hours.changeCapacity(capacity));
        assertThat(hours.getMaxConcurrentBookings()).isEqualTo(3);
    }

    @Test
    void coversIncludesTheStartAndExcludesTheEnd() {
        WorkingHours hours = valid().build();

        assertThat(hours.covers(LocalTime.of(9, 0))).isTrue();
        assertThat(hours.covers(LocalTime.of(16, 59))).isTrue();
        assertThat(hours.covers(LocalTime.of(17, 0))).isFalse();
        assertThat(hours.covers(LocalTime.of(8, 59))).isFalse();
    }
}
