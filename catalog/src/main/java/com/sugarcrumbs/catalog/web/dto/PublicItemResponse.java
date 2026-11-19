package com.sugarcrumbs.catalog.web.dto;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An item as the public storefront shows it. Leaves out what only the Owner needs:
 * the exact stock count, the availability switch (hidden items never appear) and
 * audit timestamps. {@code orderable} is false when the item is sold out.
 * {@code thumbnailUrls} follows {@code imageUrls} one for one: the small version for lists and cards.
 */
public record PublicItemResponse(
        UUID id,
        String name,
        @Nullable String description,
        BigDecimal price,
        CategoryResponse category,
        int leadTimeHours,
        boolean orderable,
        List<String> imageUrls,
        List<String> thumbnailUrls
) {
}
