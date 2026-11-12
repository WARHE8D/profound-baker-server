package com.sugarcrumbs.catalog.web.dto;

import jakarta.validation.constraints.NotNull;

public record AvailabilityRequest(
        @NotNull Boolean available
) {
}
