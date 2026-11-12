package com.sugarcrumbs.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only view of an item, as other modules see it. A value copy, not a
 * live entity, so it can travel over HTTP unchanged once the catalog is a
 * separate service.
 *
 * @param orderable    available and, if stock is tracked, in stock
 * @param stockTracked false when the item has unlimited stock
 */
public record ItemSnapshot(
        UUID id,
        String name,
        BigDecimal price,
        int leadTimeHours,
        boolean orderable,
        boolean stockTracked
) {
}
