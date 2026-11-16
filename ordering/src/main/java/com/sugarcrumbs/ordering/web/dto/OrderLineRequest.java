package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** One line of a checkout. The upper limit on {@code quantity} is configurable, so {@code OrderService} checks it. */
public record OrderLineRequest(
        @NotNull UUID itemId,
        @Min(1) int quantity
) {
}
