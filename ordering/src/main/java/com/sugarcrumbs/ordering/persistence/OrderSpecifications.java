package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import jakarta.persistence.criteria.Path;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/** The Owner's order filters. Each returns {@code null} when its filter is absent, and {@link #combine} drops those. */
public final class OrderSpecifications {

    /** Escapes LIKE's own wildcards, so a search for "50%" means those characters. */
    private static final char LIKE_ESCAPE = '\\';

    private OrderSpecifications() {
    }

    /** Pickup at or after {@code from} and before {@code to}; either end may be left open. */
    public static Specification<Order> pickupBetween(Instant from, Instant to) {
        if (from == null && to == null) {
            return null;
        }
        return (root, query, cb) -> {
            Path<Instant> pickupAt = root.get("pickupAt");
            if (from == null) {
                return cb.lessThan(pickupAt, to);
            }
            if (to == null) {
                return cb.greaterThanOrEqualTo(pickupAt, from);
            }
            return cb.and(cb.greaterThanOrEqualTo(pickupAt, from), cb.lessThan(pickupAt, to));
        };
    }

    public static Specification<Order> statusIn(Collection<OrderStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /**
     * Case-insensitive substring match on the reference and the guest's name, email and phone:
     * what the Owner has in hand when a guest calls or turns up.
     */
    public static Specification<Order> matching(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(search.strip().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("reference")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("guestContact").get("name")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("guestContact").get("email")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("guestContact").get("phone")), pattern, LIKE_ESCAPE)
        );
    }

    public static Specification<Order> combine(Instant from, Instant to, Collection<OrderStatus> statuses,
                                               String search) {
        return allOfNonNull(pickupBetween(from, to), statusIn(statuses), matching(search));
    }

    private static String escapeLike(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
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
