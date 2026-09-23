package com.sugarcrumbs.server.entity;

import com.sugarcrumbs.server.entity.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Objects;

/**
 * A date range the owner is unavailable — vacation, a holiday, a
 * one-off closure. Distinct from {@link WorkingHours} on purpose: weekly
 * hours are the *default* schedule, blocked dates are *exceptions* to
 * it. Keeping them as separate entities means adding a holiday never
 * requires touching the recurring template.
 */
@Entity
@Table(
        name = "blocked_dates",
        indexes = {
                @Index(name = "idx_blocked_dates_owner", columnList = "owner_id"),
                @Index(name = "idx_blocked_dates_range", columnList = "start_date, end_date")
        }
)
public class BlockedDate extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, foreignKey = @ForeignKey(name = "fk_blocked_dates_owner"))
    private Owner owner;

    @NotNull
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @NotNull
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Size(max = 200)
    @Column(name = "reason", length = 200)
    private String reason;

    /** True for things like "every Dec 25" — matched by month/day, not by year. */
    @Column(name = "recurring_annually", nullable = false)
    private boolean recurringAnnually;

    protected BlockedDate() {
        // required by JPA
    }

    private BlockedDate(Builder builder) {
        this.owner = Objects.requireNonNull(builder.owner, "owner is required");
        this.reason = builder.reason;
        this.recurringAnnually = builder.recurringAnnually;
        setRange(builder.startDate, builder.endDate);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Business behavior ----

    public void reschedule(LocalDate newStartDate, LocalDate newEndDate) {
        setRange(newStartDate, newEndDate);
    }

    public void updateReason(String newReason) {
        this.reason = newReason;
    }

    public void setRecurringAnnually(boolean recurringAnnually) {
        this.recurringAnnually = recurringAnnually;
    }

    /**
     * Whether the given date falls inside this block. For recurring
     * entries, only the month/day is compared (the year is ignored);
     * for one-off entries, the exact date range is used.
     */
    public boolean covers(LocalDate date) {
        if (recurringAnnually) {
            MonthDay target = MonthDay.from(date);
            return !target.isBefore(MonthDay.from(startDate)) && !target.isAfter(MonthDay.from(endDate));
        }
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    private void setRange(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "startDate is required");
        Objects.requireNonNull(end, "endDate is required");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("endDate cannot be before startDate");
        }
        this.startDate = start;
        this.endDate = end;
    }

    // ---- Getters only ----

    public Owner getOwner() {
        return owner;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getReason() {
        return reason;
    }

    public boolean isRecurringAnnually() {
        return recurringAnnually;
    }

    @Override
    public String toString() {
        return "BlockedDate{id=%s, start=%s, end=%s, recurring=%s}"
                .formatted(getId(), startDate, endDate, recurringAnnually);
    }

    public static final class Builder {
        private Owner owner;
        private LocalDate startDate;
        private LocalDate endDate;
        private String reason;
        private boolean recurringAnnually;

        private Builder() {
        }

        public Builder owner(Owner owner) {
            this.owner = owner;
            return this;
        }

        public Builder startDate(LocalDate startDate) {
            this.startDate = startDate;
            return this;
        }

        public Builder endDate(LocalDate endDate) {
            this.endDate = endDate;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder recurringAnnually(boolean recurringAnnually) {
            this.recurringAnnually = recurringAnnually;
            return this;
        }

        public BlockedDate build() {
            return new BlockedDate(this);
        }
    }
}
