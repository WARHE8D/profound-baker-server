package com.sugarcrumbs.ordering.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The order aggregate: lines carry their own price snapshot, and the state
 * machine allows every listed transition and refuses (unchanged) every other.
 *
 * <pre>
 * PENDING → CONFIRMED → IN_PROGRESS → FULFILLED
 *    │          └──────────────────────↗
 *    └→ REJECTED
 * PENDING / CONFIRMED / IN_PROGRESS → CANCELLED
 * </pre>
 *
 * Lines and notes can be edited only while the order is pending (#16).
 */
class OrderTests {

    private static final Instant PICKUP = Instant.parse("2026-12-01T10:00:00Z");

    private static Order.Builder valid() {
        return Order.builder()
                .reference("SC-7F3K2")
                .pickupAt(PICKUP)
                .guestContact(new GuestContact("Ada", "Ada@Example.com", "+1 555 0100"))
                .line(UUID.randomUUID(), "Sourdough", new BigDecimal("6.50"), 1);
    }

    /** An order driven into the given status through the allowed transitions only. */
    private static Order inStatus(OrderStatus status) {
        Order order = valid().build();
        switch (status) {
            case PENDING -> { }
            case CONFIRMED -> order.confirm();
            case IN_PROGRESS -> {
                order.confirm();
                order.startPreparation();
            }
            case FULFILLED -> {
                order.confirm();
                order.fulfill();
            }
            case REJECTED -> order.reject("fully booked");
            case CANCELLED -> order.cancel("changed plans");
        }
        assertThat(order.getStatus()).isEqualTo(status);
        return order;
    }

    @Nested
    class Creation {

        @Test
        void startsPendingWithItsReference() {
            Order order = valid().build();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getReference()).isEqualTo("SC-7F3K2");
            assertThat(order.getCancellationReason()).isNull();
        }

        @Test
        void requiresTimeContactAndReference() {
            assertThatThrownBy(() -> valid().pickupAt(null).build()).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> valid().guestContact(null).build()).isInstanceOf(NullPointerException.class);
            assertThatIllegalArgumentException().isThrownBy(() -> valid().reference(null).build());
        }

        @ParameterizedTest
        @ValueSource(strings = {"SC-7f3k2", "SC-7F3K", "SC-7F3KI", "XX-7F3K2", "SC-L12"})
        void refusesMalformedReferences(String reference) {
            assertThatIllegalArgumentException().isThrownBy(() -> valid().reference(reference).build());
        }

        @Test
        void acceptsLegacyReferencesFromMigratedBookings() {
            assertThat(valid().reference("SC-L0001").build().getReference()).isEqualTo("SC-L0001");
            assertThat(valid().reference("SC-L12345").build().getReference()).isEqualTo("SC-L12345");
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
    class Lines {

        @Test
        void linesKeepTheirOrderAndPriceSnapshot() {
            UUID bread = UUID.randomUUID();
            UUID cake = UUID.randomUUID();
            Order order = Order.builder().reference("SC-7F3K2").pickupAt(PICKUP)
                    .guestContact(new GuestContact("Ada", "a@b.c", "1"))
                    .line(bread, "Sourdough", new BigDecimal("6.5"), 2)
                    .line(cake, "Lemon cake", new BigDecimal("24.99"), 1)
                    .build();

            assertThat(order.getLines()).extracting(OrderLine::getLineNo).containsExactly(1, 2);
            OrderLine first = order.getLines().getFirst();
            assertThat(first.getItemId()).isEqualTo(bread);
            assertThat(first.getItemName()).isEqualTo("Sourdough");
            assertThat(first.getUnitPrice()).isEqualByComparingTo("6.50").hasScaleOf(2);
            assertThat(first.getLineTotal()).isEqualByComparingTo("13.00");
            assertThat(order.getSubtotal()).isEqualTo(new BigDecimal("37.99"));
        }

        @Test
        void needsAtLeastOneLine() {
            Order.Builder empty = Order.builder().reference("SC-7F3K2").pickupAt(PICKUP)
                    .guestContact(new GuestContact("Ada", "a@b.c", "1"));

            assertThatIllegalArgumentException().isThrownBy(empty::build).withMessageContaining("at least one line");
        }

        @Test
        void anItemMayAppearOnlyOnce() {
            UUID item = UUID.randomUUID();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> valid().line(item, "A", BigDecimal.ONE, 1).line(item, "A", BigDecimal.ONE, 2).build())
                    .withMessageContaining("only once");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void aLineNeedsAPositiveQuantity(int quantity) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> valid().line(UUID.randomUUID(), "Roll", BigDecimal.ONE, quantity).build());
        }

        @Test
        void aLineNeedsANameAndANonNegativePrice() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> valid().line(UUID.randomUUID(), " ", BigDecimal.ONE, 1).build());
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> valid().line(UUID.randomUUID(), "Roll", new BigDecimal("-0.01"), 1).build());
        }

        @Test
        void theLineListCannotBeChangedFromOutside() {
            Order order = valid().build();

            assertThatThrownBy(() -> order.getLines().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class AllowedTransitions {

        @Test
        void confirmThenPrepareThenFulfil() {
            Order order = valid().build();

            order.confirm();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

            order.startPreparation();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);

            order.fulfill();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        }

        @Test
        void aConfirmedOrderCanBeFulfilledWithoutAPreparationStep() {
            Order order = inStatus(OrderStatus.CONFIRMED);

            order.fulfill();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "CONFIRMED", "IN_PROGRESS"})
        void openOrdersCanBeCancelledWithAReason(OrderStatus from) {
            Order order = inStatus(from);

            order.cancel("oven broke");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancellationReason()).isEqualTo("oven broke");
        }

        @Test
        void aPendingOrderCanBeRejectedWithAReason() {
            Order order = valid().build();

            order.reject("  fully booked that week ");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getRejectionReason()).isEqualTo("fully booked that week");
            assertThat(order.getCancellationReason()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "CONFIRMED", "IN_PROGRESS"})
        void openOrdersCanBeRescheduled(OrderStatus from) {
            Order order = inStatus(from);
            Instant later = PICKUP.plusSeconds(3600);

            order.reschedule(later);

            assertThat(order.getPickupAt()).isEqualTo(later);
            assertThat(order.getStatus()).isEqualTo(from);
        }
    }

    @Nested
    class RefusedTransitions {

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        void onlyAPendingOrderCanBeConfirmed(OrderStatus from) {
            assertRefused(from, Order::confirm);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
        void onlyAConfirmedOrderCanStartPreparation(OrderStatus from) {
            assertRefused(from, Order::startPreparation);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "FULFILLED", "REJECTED", "CANCELLED"})
        void fulfilRequiresAConfirmedOrInProgressOrder(OrderStatus from) {
            assertRefused(from, Order::fulfill);
        }

        /** Once confirmed, calling an order off is a cancellation, never a rejection. */
        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        void onlyAPendingOrderCanBeRejected(OrderStatus from) {
            assertRefused(from, order -> order.reject("no"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void aRejectionNeedsAReason(String reason) {
            Order order = valid().build();

            assertThatIllegalArgumentException().isThrownBy(() -> order.reject(reason));
            assertThatIllegalArgumentException().isThrownBy(() -> order.reject(null));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"FULFILLED", "REJECTED", "CANCELLED"})
        void finishedOrdersCannotBeCancelled(OrderStatus from) {
            assertRefused(from, order -> order.cancel("too late"));
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"FULFILLED", "REJECTED", "CANCELLED"})
        void finishedOrdersCannotBeRescheduled(OrderStatus from) {
            Order order = inStatus(from);

            assertThatIllegalStateException().isThrownBy(() -> order.reschedule(PICKUP.plusSeconds(60)));
            assertThat(order.getPickupAt()).isEqualTo(PICKUP);
        }

        @Test
        void aCancelledOrderKeepsItsOriginalReason() {
            Order order = inStatus(OrderStatus.CANCELLED);

            assertThatIllegalStateException().isThrownBy(() -> order.cancel("second reason"));
            assertThat(order.getCancellationReason()).isEqualTo("changed plans");
        }

        private void assertRefused(OrderStatus from, Consumer<Order> transition) {
            Order order = inStatus(from);

            assertThatIllegalStateException().isThrownBy(() -> transition.accept(order));
            assertThat(order.getStatus()).isEqualTo(from);
        }
    }

    @Nested
    class Edits {

        private final UUID bread = UUID.randomUUID();
        private final UUID cake = UUID.randomUUID();
        private final UUID roll = UUID.randomUUID();

        private Order breadAndCake() {
            return Order.builder().reference("SC-7F3K2").pickupAt(PICKUP)
                    .guestContact(new GuestContact("Ada", "a@b.c", "1"))
                    .line(bread, "Sourdough", new BigDecimal("6.50"), 2)
                    .line(cake, "Lemon cake", new BigDecimal("24.99"), 1)
                    .build();
        }

        @Test
        void rescheduleRequiresATime() {
            assertThatThrownBy(() -> valid().build().reschedule(null)).isInstanceOf(NullPointerException.class);
        }

        /** The guest's agreed price survives even if the edit carries a different one. */
        @Test
        void keptLinesChangeOnlyTheirQuantityNewOnesGoAtTheEndRemovedOnesGo() {
            Order order = breadAndCake();

            order.editLines(List.of(
                    new Order.LineDraft(roll, "Cinnamon roll", new BigDecimal("3.00"), 4),
                    new Order.LineDraft(bread, "Renamed", new BigDecimal("99.00"), 3)));

            assertThat(order.getLines()).extracting(OrderLine::getItemId).containsExactly(bread, roll);
            OrderLine kept = order.getLines().getFirst();
            assertThat(kept.getLineNo()).isEqualTo(1);
            assertThat(kept.getItemName()).isEqualTo("Sourdough");
            assertThat(kept.getUnitPrice()).isEqualByComparingTo("6.50");
            assertThat(kept.getQuantity()).isEqualTo(3);
            assertThat(kept.getLineTotal()).isEqualByComparingTo("19.50");
            assertThat(order.getLines().get(1).getLineNo()).as("after every old line, removed ones included")
                    .isEqualTo(3);
            assertThat(order.getSubtotal()).isEqualByComparingTo("31.50");
        }

        @Test
        void anEditMustLeaveAtLeastOneLineAndEachItemOnce() {
            Order order = breadAndCake();

            assertThatIllegalArgumentException().isThrownBy(() -> order.editLines(List.of()));
            assertThatIllegalArgumentException().isThrownBy(() -> order.editLines(List.of(
                    new Order.LineDraft(roll, "Roll", BigDecimal.ONE, 1),
                    new Order.LineDraft(roll, "Roll", BigDecimal.ONE, 2))));
            assertThat(order.getLines()).extracting(OrderLine::getQuantity).containsExactly(2, 1);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
        void onlyAPendingOrdersLinesAndNotesCanBeEdited(OrderStatus from) {
            Order order = inStatus(from);
            List<Order.LineDraft> edit = List.of(new Order.LineDraft(roll, "Roll", BigDecimal.ONE, 1));

            assertThatIllegalStateException().isThrownBy(() -> order.editLines(edit));
            assertThatIllegalStateException().isThrownBy(() -> order.updateNotes("extra candles"));
            assertThat(order.getLines()).hasSize(1);
            assertThat(order.getNotes()).isNull();
        }

        @Test
        void aPendingOrdersNotesCanBeChanged() {
            Order order = valid().build();

            order.updateNotes("Happy birthday, Sam");

            assertThat(order.getNotes()).isEqualTo("Happy birthday, Sam");
        }
    }
}
