package com.sugarcrumbs.catalog.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An item as the public storefront shows it. Leaves out what only the Owner needs:
 * the exact stock count, the availability switch (hidden items never appear) and
 * audit timestamps. {@code orderable} is false when the item is sold out.
 */
public record PublicItemResponse(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        CategoryResponse category,
        int leadTimeHours,
        boolean orderable,
        List<String> imageUrls
) {
}
