package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Replaces the discount; {@code 0} removes it. It may not exceed the subtotal. */
public record DiscountRequest(
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal amount
) {
}
