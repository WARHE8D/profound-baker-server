package com.sugarcrumbs.server.entity;

import com.sugarcrumbs.server.entity.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A recurring weekly working-hours template, e.g. "Tuesday, 9am–5pm,
 * 30-minute slots, max 2 concurrent bookings per slot".
 *
 * <p>Uses the JDK's own {@link DayOfWeek} rather than a
 * hand-rolled enum — no reason to reinvent a type the standard library
 * already models correctly (including locale-aware display names).
 *
 * <p>Even though the system is single-tenant today, this links to
 * {@link Owner} rather than assuming a singleton — cheap to include now,
 * expensive to retrofit later, and it makes the intent of the table
 * unambiguous.
 */
@Entity
@Table(
        name = "working_hours",
        uniqueConstraints = @UniqueConstraint(name = "uk_working_hours_owner_day", columnNames = {"owner_id", "day_of_week"}),
        indexes = @Index(name = "idx_working_hours_owner", columnList = "owner_id")
)
public class WorkingHours extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, foreignKey = @ForeignKey(name = "fk_working_hours_owner"))
    private Owner owner;

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
    @Min(5)
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
        this.owner = Objects.requireNonNull(builder.owner, "owner is required");
        this.dayOfWeek = Objects.requireNonNull(builder.dayOfWeek, "dayOfWeek is required");
        this.slotDurationMinutes = requirePositive(builder.slotDurationMinutes, "slotDurationMinutes");
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
        this.slotDurationMinutes = requirePositive(minutes, "slotDurationMinutes");
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

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    // ---- Getters only ----

    public Owner getOwner() {
        return owner;
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
        private Owner owner;
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;
        private int slotDurationMinutes = 30;
        private int maxConcurrentBookings = 1;

        private Builder() {
        }

        public Builder owner(Owner owner) {
            this.owner = owner;
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
