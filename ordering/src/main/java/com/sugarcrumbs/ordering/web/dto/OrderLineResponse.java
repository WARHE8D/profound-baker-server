package com.sugarcrumbs.ordering.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A line as ordered: the name and unit price are the snapshot taken at checkout. */
public record OrderLineResponse(
        int lineNo,
        UUID itemId,
        String itemName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal
) {
}
