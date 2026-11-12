package com.sugarcrumbs.catalog.persistence;

import com.sugarcrumbs.catalog.domain.Item;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Small, composable filter predicates. Callers AND together only the
 * ones they need via {@link Specification#and}, so an unfiltered
 * "browse everything" request and a "cakes only, under $50, available
 * now" request go through the exact same query path.
 */
public final class ItemSpecifications {

    private ItemSpecifications() {
    }

    public static Specification<Item> categoryIdEquals(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Item> availableEquals(Boolean available) {
        if (available == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("available"), available);
    }

    /** Case-insensitive substring match against name and description. */
    public static Specification<Item> nameOrDescriptionContains(String searchText) {
        if (!StringUtils.hasText(searchText)) {
            return null;
        }
        String pattern = "%" + searchText.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("description")), pattern)
        );
    }

    /** Combines only the non-null specs; returns "match everything" if all filters are absent. */
    public static Specification<Item> combine(UUID categoryId, Boolean available, String searchText) {
        return allOfNonNull(
                categoryIdEquals(categoryId),
                availableEquals(available),
                nameOrDescriptionContains(searchText)
        );
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
