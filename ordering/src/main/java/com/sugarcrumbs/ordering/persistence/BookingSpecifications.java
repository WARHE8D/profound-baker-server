package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.Booking;
import com.sugarcrumbs.ordering.domain.BookingStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public final class BookingSpecifications {

    private BookingSpecifications() {
    }

    public static Specification<Booking> scheduledForBetween(Instant from, Instant to) {
        if (from == null || to == null) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("scheduledFor"), from),
                cb.lessThan(root.get("scheduledFor"), to)
        );
    }

    public static Specification<Booking> statusEquals(BookingStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Booking> combine(Instant from, Instant to, BookingStatus status) {
        return allOfNonNull(scheduledForBetween(from, to), statusEquals(status));
    }

    /**
     * Spring Data 4 rejects null specs in {@code Specification.allOf}, so absent
     * filters are dropped first. With no filters left, {@code allOf()} matches everything.
     */
    @SafeVarargs
    private static <T> Specification<T> allOfNonNull(Specification<T>... specs) {
        return Specification.allOf(Arrays.stream(specs).filter(Objects::nonNull).toList());
    }
}
