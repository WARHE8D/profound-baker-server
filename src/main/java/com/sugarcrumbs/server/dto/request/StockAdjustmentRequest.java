package com.sugarcrumbs.server.dto.request;

import jakarta.validation.constraints.NotNull;

/** delta may be negative (decrement) or positive (restock); zero is rejected at the service layer as a no-op mistake. */
public record StockAdjustmentRequest(
        @NotNull Integer delta
) {
}
