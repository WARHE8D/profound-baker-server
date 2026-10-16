package com.sugarcrumbs.server.repository.booking;


import com.sugarcrumbs.server.entity.Booking;
import com.sugarcrumbs.server.entity.BookingStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

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
        return Specification.allOf(scheduledForBetween(from, to), statusEquals(status));
    }
}
