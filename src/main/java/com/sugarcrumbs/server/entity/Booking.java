package com.sugarcrumbs.server.entity;

import com.sugarcrumbs.server.entity.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A guest's scheduled order/appointment for one menu {@link Item}.
 *
 * <p>No {@code Customer} entity — this app uses guest checkout, so
 * contact details live inline as a {@link GuestContact} value object
 * rather than a foreign key to an account.
 *
 * <p>Out of scope by design: multiple items per booking. If that's ever
 * needed, introduce a {@code BookingLineItem} child entity and turn
 * {@code item}/{@code quantity} into a {@code @OneToMany} — not done
 * here since only a single-item booking was asked for, and adding
 * unused flexibility now would just be speculative complexity.
 *
 * <p>Status transitions are guarded in code (see {@link #confirm()} etc.)
 * so a booking can never silently jump from, say, {@code CANCELLED}
 * back to {@code CONFIRMED} through a careless update.
 */
@Entity
@Table(
        name = "bookings",
        indexes = {
                @Index(name = "idx_bookings_scheduled_for", columnList = "scheduled_for"),
                @Index(name = "idx_bookings_status", columnList = "status"),
                @Index(name = "idx_bookings_item", columnList = "item_id")
        }
)
public class Booking extends BaseEntity {

    private static final Set<BookingStatus> CANCELLABLE_FROM =
            EnumSet.of(BookingStatus.PENDING, BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS);

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false, foreignKey = @ForeignKey(name = "fk_bookings_item"))
    private Item item;

    @Min(1)
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @NotNull
    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Embedded
    private GuestContact guestContact;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    @Size(max = 300)
    @Column(name = "cancellation_reason", length = 300)
    private String cancellationReason;

    protected Booking() {
        // required by JPA
    }

    private Booking(Builder builder) {
        this.item = Objects.requireNonNull(builder.item, "item is required");
        this.quantity = requirePositive(builder.quantity, "quantity");
        this.scheduledFor = Objects.requireNonNull(builder.scheduledFor, "scheduledFor is required");
        this.guestContact = Objects.requireNonNull(builder.guestContact, "guestContact is required");
        this.notes = builder.notes;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Business behavior: explicit state machine, not a raw setter ----

    public void confirm() {
        requireStatus(BookingStatus.PENDING, "confirm");
        this.status = BookingStatus.CONFIRMED;
    }

    public void startPreparation() {
        requireStatus(BookingStatus.CONFIRMED, "start preparation on");
        this.status = BookingStatus.IN_PROGRESS;
    }

    public void fulfill() {
        if (status != BookingStatus.CONFIRMED && status != BookingStatus.IN_PROGRESS) {
            throw new IllegalStateException("cannot fulfill a booking in status " + status);
        }
        this.status = BookingStatus.FULFILLED;
    }

    public void cancel(String reason) {
        if (!CANCELLABLE_FROM.contains(status)) {
            throw new IllegalStateException("cannot cancel a booking in status " + status);
        }
        this.status = BookingStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public void reschedule(Instant newScheduledFor) {
        if (status == BookingStatus.CANCELLED || status == BookingStatus.FULFILLED) {
            throw new IllegalStateException("cannot reschedule a booking in status " + status);
        }
        this.scheduledFor = Objects.requireNonNull(newScheduledFor, "scheduledFor is required");
    }

    public void changeQuantity(int newQuantity) {
        this.quantity = requirePositive(newQuantity, "quantity");
    }

    public void updateNotes(String newNotes) {
        this.notes = newNotes;
    }

    private void requireStatus(BookingStatus required, String action) {
        if (status != required) {
            throw new IllegalStateException("cannot " + action + " a booking in status " + status);
        }
    }

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    // ---- Getters only ----

    public Item getItem() {
        return item;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public GuestContact getGuestContact() {
        return guestContact;
    }

    public String getNotes() {
        return notes;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    @Override
    public String toString() {
        return "Booking{id=%s, status=%s, scheduledFor=%s, quantity=%d}"
                .formatted(getId(), status, scheduledFor, quantity);
    }

    public static final class Builder {
        private Item item;
        private int quantity = 1;
        private Instant scheduledFor;
        private GuestContact guestContact;
        private String notes;

        private Builder() {
        }

        public Builder item(Item item) {
            this.item = item;
            return this;
        }

        public Builder quantity(int quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder scheduledFor(Instant scheduledFor) {
            this.scheduledFor = scheduledFor;
            return this;
        }

        public Builder guestContact(GuestContact guestContact) {
            this.guestContact = guestContact;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public Booking build() {
            return new Booking(this);
        }
    }
}
