package com.sugarcrumbs.ordering.web.dto;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/** A line as ordered: the name and unit price are the snapshot taken at checkout. */
public record OrderLineResponse(
        int lineNo,
        @Nullable UUID itemId,
        String itemName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal
) {
}
