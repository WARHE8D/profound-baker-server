package com.sugarcrumbs.ordering.domain;

import com.sugarcrumbs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A recurring weekly working-hours template, e.g. "Tuesday, 9am–5pm,
 * 30-minute slots, max 2 concurrent bookings per slot".
 *
 * <p>Uses the JDK's own {@link DayOfWeek} rather than a
 * hand-rolled enum — no reason to reinvent a type the standard library
 * already models correctly (including locale-aware display names).
 *
 * <p>Even though the system is single-tenant today, this links to
 * Owner rather than assuming a singleton — cheap to include now,
 * expensive to retrofit later, and it makes the intent of the table
 * unambiguous.
 */
@Entity
@Table(
        schema = "ordering",
        name = "working_hours",
        uniqueConstraints = @UniqueConstraint(name = "uk_working_hours_owner_day", columnNames = {"owner_id", "day_of_week"}),
        indexes = @Index(name = "idx_working_hours_owner", columnList = "owner_id")
)
public class WorkingHours extends BaseEntity {

    /** Same floor as the database check and {@code WorkingHoursRequest}; shorter slots aren't meaningful for pickups. */
    public static final int MIN_SLOT_DURATION_MINUTES = 5;

    @NotNull
    /** The Owner's id. A plain UUID, not a relation: the Owner lives in the identity module (see {@code OwnerApi}). */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @NotNull
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @NotNull
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** Granularity used when generating bookable slots from this window. */
    @Min(MIN_SLOT_DURATION_MINUTES)
    @Column(name = "slot_duration_minutes", nullable = false)
    private int slotDurationMinutes;

    /** How many bookings can share the same slot (e.g. 1 for consultations, higher for small pickup orders). */
    @Min(1)
    @Column(name = "max_concurrent_bookings", nullable = false)
    private int maxConcurrentBookings;

    protected WorkingHours() {
        // required by JPA
    }

    private WorkingHours(Builder builder) {
        this.ownerId = Objects.requireNonNull(builder.ownerId, "ownerId is required");
        this.dayOfWeek = Objects.requireNonNull(builder.dayOfWeek, "dayOfWeek is required");
        this.slotDurationMinutes = requireSlotDuration(builder.slotDurationMinutes);
        this.maxConcurrentBookings = requirePositive(builder.maxConcurrentBookings, "maxConcurrentBookings");
        setWindow(builder.startTime, builder.endTime);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Business behavior ----

    public void reschedule(LocalTime newStartTime, LocalTime newEndTime) {
        setWindow(newStartTime, newEndTime);
    }

    public void changeSlotDuration(int minutes) {
        this.slotDurationMinutes = requireSlotDuration(minutes);
    }

    public void changeCapacity(int maxConcurrentBookings) {
        this.maxConcurrentBookings = requirePositive(maxConcurrentBookings, "maxConcurrentBookings");
    }

    /** Whether the given time falls within this window (inclusive start, exclusive end). */
    public boolean covers(LocalTime time) {
        return !time.isBefore(startTime) && time.isBefore(endTime);
    }

    private void setWindow(LocalTime start, LocalTime end) {
        Objects.requireNonNull(start, "startTime is required");
        Objects.requireNonNull(end, "endTime is required");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("startTime must be before endTime");
        }
        this.startTime = start;
        this.endTime = end;
    }

    private static int requireSlotDuration(int minutes) {
        if (minutes < MIN_SLOT_DURATION_MINUTES) {
            throw new IllegalArgumentException("slotDurationMinutes must be at least " + MIN_SLOT_DURATION_MINUTES);
        }
        return minutes;
    }

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    // ---- Getters only ----

    public UUID getOwnerId() {
        return ownerId;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getSlotDurationMinutes() {
        return slotDurationMinutes;
    }

    public int getMaxConcurrentBookings() {
        return maxConcurrentBookings;
    }

    @Override
    public String toString() {
        return "WorkingHours{id=%s, dayOfWeek=%s, start=%s, end=%s}"
                .formatted(getId(), dayOfWeek, startTime, endTime);
    }

    public static final class Builder {
        private UUID ownerId;
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;
        private int slotDurationMinutes = 30;
        private int maxConcurrentBookings = 1;

        private Builder() {
        }

        public Builder ownerId(UUID ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        public Builder dayOfWeek(DayOfWeek dayOfWeek) {
            this.dayOfWeek = dayOfWeek;
            return this;
        }

        public Builder startTime(LocalTime startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(LocalTime endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder slotDurationMinutes(int slotDurationMinutes) {
            this.slotDurationMinutes = slotDurationMinutes;
            return this;
        }

        public Builder maxConcurrentBookings(int maxConcurrentBookings) {
            this.maxConcurrentBookings = maxConcurrentBookings;
            return this;
        }

        public WorkingHours build() {
            return new WorkingHours(this);
        }
    }
}
