package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> pickupBetween(Instant from, Instant to) {
        if (from == null || to == null) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("pickupAt"), from),
                cb.lessThan(root.get("pickupAt"), to)
        );
    }

    public static Specification<Order> statusEquals(OrderStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Order> combine(Instant from, Instant to, OrderStatus status) {
        return allOfNonNull(pickupBetween(from, to), statusEquals(status));
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
