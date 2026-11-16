package com.sugarcrumbs.ordering.persistence;

import com.sugarcrumbs.ordering.domain.Invoice;
import com.sugarcrumbs.ordering.domain.InvoiceStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/** The Owner's invoice filters. Each returns {@code null} when its filter is absent, and {@link #combine} drops those. */
public final class InvoiceSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private InvoiceSpecifications() {
    }

    /** Issued at or after {@code from} and before {@code to}; either end may be left open. */
    public static Specification<Invoice> issuedBetween(Instant from, Instant to) {
        if (from == null && to == null) {
            return null;
        }
        return (root, query, cb) -> {
            if (from == null) {
                return cb.lessThan(root.get("createdAt"), to);
            }
            if (to == null) {
                return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            }
            return cb.and(cb.greaterThanOrEqualTo(root.get("createdAt"), from), cb.lessThan(root.get("createdAt"), to));
        };
    }

    public static Specification<Invoice> statusIn(Collection<InvoiceStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /** Case-insensitive substring match on the invoice number and its order's reference. */
    public static Specification<Invoice> matching(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(search.strip().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("number")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("order").get("reference")), pattern, LIKE_ESCAPE));
    }

    public static Specification<Invoice> combine(Instant from, Instant to, Collection<InvoiceStatus> statuses,
                                                 String search) {
        return allOfNonNull(issuedBetween(from, to), statusIn(statuses), matching(search));
    }

    /** Spring Data 4 rejects null specs in {@code Specification.allOf}, so absent filters are dropped first. */
    @SafeVarargs
    private static <T> Specification<T> allOfNonNull(Specification<T>... specs) {
        return Specification.allOf(Arrays.stream(specs).filter(Objects::nonNull).toList());
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
}
