package com.sugarcrumbs.ordering.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The booking state machine: every allowed transition works, every other
 * one is refused and leaves the booking unchanged.
 *
 * <pre>
 * PENDING → CONFIRMED → IN_PROGRESS → FULFILLED
 *              └──────────────────────↗
 * PENDING / CONFIRMED / IN_PROGRESS → CANCELLED
 * </pre>
 */
class BookingTests {

    private static final Instant PICKUP = Instant.parse("2026-12-01T10:00:00Z");

    private static Booking.Builder valid() {
        return Booking.builder()
                .itemId(UUID.randomUUID())
                .scheduledFor(PICKUP)
                .guestContact(new GuestContact("Ada", "Ada@Example.com", "+1 555 0100"));
    }

    /** A booking driven into the given status through the allowed transitions only. */
    private static Booking inStatus(BookingStatus status) {
        Booking booking = valid().build();
        switch (status) {
            case PENDING -> { }
            case CONFIRMED -> booking.confirm();
            case IN_PROGRESS -> {
                booking.confirm();
                booking.startPreparation();
            }
            case FULFILLED -> {
                booking.confirm();
                booking.fulfill();
            }
            case CANCELLED -> booking.cancel("changed plans");
        }
        assertThat(booking.getStatus()).isEqualTo(status);
        return booking;
    }

    @Nested
    class Creation {

        @Test
        void startsPendingWithQuantityOne() {
            Booking booking = valid().build();

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
            assertThat(booking.getQuantity()).isEqualTo(1);
            assertThat(booking.getCancellationReason()).isNull();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void requiresAPositiveQuantity(int quantity) {
            assertThatIllegalArgumentException().isThrownBy(() -> valid().quantity(quantity).build());
        }

        @Test
        void requiresItemTimeAndContact() {
            assertThatThrownBy(() -> valid().itemId(null).build()).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> valid().scheduledFor(null).build()).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> valid().guestContact(null).build()).isInstanceOf(NullPointerException.class);
        }

        @Test
        void guestEmailIsStoredLowerCaseAndBlankContactDetailsAreRefused() {
            assertThat(valid().build().getGuestContact().getEmail()).isEqualTo("ada@example.com");

            assertThatIllegalArgumentException().isThrownBy(() -> new GuestContact(" ", "a@b.c", "1"));
            assertThatIllegalArgumentException().isThrownBy(() -> new GuestContact("Ada", null, "1"));
            assertThatIllegalArgumentException().isThrownBy(() -> new GuestContact("Ada", "a@b.c", ""));
        }
    }

    @Nested
    class AllowedTransitions {

        @Test
        void confirmThenPrepareThenFulfil() {
            Booking booking = valid().build();

            booking.confirm();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

            booking.startPreparation();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_PROGRESS);

            booking.fulfill();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.FULFILLED);
        }

        @Test
        void aConfirmedBookingCanBeFulfilledWithoutAPreparationStep() {
            Booking booking = inStatus(BookingStatus.CONFIRMED);

            booking.fulfill();

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.FULFILLED);
        }

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED", "IN_PROGRESS"})
        void openBookingsCanBeCancelledWithAReason(BookingStatus from) {
            Booking booking = inStatus(from);

            booking.cancel("oven broke");

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(booking.getCancellationReason()).isEqualTo("oven broke");
        }

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED", "IN_PROGRESS"})
        void openBookingsCanBeRescheduled(BookingStatus from) {
            Booking booking = inStatus(from);
            Instant later = PICKUP.plusSeconds(3600);

            booking.reschedule(later);

            assertThat(booking.getScheduledFor()).isEqualTo(later);
            assertThat(booking.getStatus()).isEqualTo(from);
        }
    }

    @Nested
    class RefusedTransitions {

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        void onlyAPendingBookingCanBeConfirmed(BookingStatus from) {
            assertRefused(from, Booking::confirm);
        }

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
        void onlyAConfirmedBookingCanStartPreparation(BookingStatus from) {
            assertRefused(from, Booking::startPreparation);
        }

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class, names = {"PENDING", "FULFILLED", "CANCELLED"})
        void fulfilRequiresAConfirmedOrInProgressBooking(BookingStatus from) {
            assertRefused(from, Booking::fulfill);
        }

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class, names = {"FULFILLED", "CANCELLED"})
        void finishedBookingsCannotBeCancelled(BookingStatus from) {
            assertRefused(from, booking -> booking.cancel("too late"));
        }

        @ParameterizedTest
        @EnumSource(value = BookingStatus.class, names = {"FULFILLED", "CANCELLED"})
        void finishedBookingsCannotBeRescheduled(BookingStatus from) {
            Booking booking = inStatus(from);

            assertThatIllegalStateException().isThrownBy(() -> booking.reschedule(PICKUP.plusSeconds(60)));
            assertThat(booking.getScheduledFor()).isEqualTo(PICKUP);
        }

        @Test
        void aCancelledBookingKeepsItsOriginalReason() {
            Booking booking = inStatus(BookingStatus.CANCELLED);

            assertThatIllegalStateException().isThrownBy(() -> booking.cancel("second reason"));
            assertThat(booking.getCancellationReason()).isEqualTo("changed plans");
        }

        private void assertRefused(BookingStatus from, Consumer<Booking> transition) {
            Booking booking = inStatus(from);

            assertThatIllegalStateException().isThrownBy(() -> transition.accept(booking));
            assertThat(booking.getStatus()).isEqualTo(from);
        }
    }

    @Nested
    class Edits {

        @Test
        void rescheduleRequiresATime() {
            assertThatThrownBy(() -> valid().build().reschedule(null)).isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -2})
        void changeQuantityRequiresAPositiveNumber(int quantity) {
            Booking booking = valid().quantity(3).build();

            assertThatIllegalArgumentException().isThrownBy(() -> booking.changeQuantity(quantity));
            assertThat(booking.getQuantity()).isEqualTo(3);
        }
    }
}
