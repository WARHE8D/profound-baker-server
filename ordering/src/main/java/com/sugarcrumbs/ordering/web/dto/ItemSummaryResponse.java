package com.sugarcrumbs.ordering.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A booking doesn't need the item's full description/images/stock — just enough to identify and price it. */
public record ItemSummaryResponse(
        UUID id,
        String name,
        BigDecimal price
) {
}
