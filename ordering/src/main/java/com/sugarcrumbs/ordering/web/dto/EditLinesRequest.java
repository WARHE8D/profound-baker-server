package com.sugarcrumbs.ordering.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The full list of lines a pending order should have: items left out are removed. Same limits as
 * checkout ({@code app.order.max-lines}, {@code app.order.max-quantity-per-line}), checked by
 * {@code OrderService}.
 */
public record EditLinesRequest(
        @NotEmpty List<@NotNull @Valid OrderLineRequest> lines
) {
}
