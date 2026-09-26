package com.sugarcrumbs.server.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Covers full create/replace of an item's descriptive fields. Deliberately
 * does NOT include availability toggling or stock adjustment — those are
 * separate, narrower endpoints/DTOs (see {@link AvailabilityRequest},
 * {@link StockAdjustmentRequest}) because those actions have their own
 * invariants ({@code Item.adjustStock} rejects going negative,
 * {@code markAvailable}/{@code markUnavailable} are simple flips) that
 * are clearer as intent-revealing operations than as fields on a big
 * generic "update everything" payload.
 */
public record ItemRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotNull UUID categoryId,
        @Min(0) int leadTimeHours,
        Integer stockQuantity
) {
}
